package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.Recorder
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleOutcome
import dev.kotlinds.pokemonclient.state.BattleState
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * [ChainRunner.forAgent]: the one wiring of an agent's chain on the recorder, shared by the app's sessions, the MCP
 * and the bench (each step is progress and named, the IDLE limit reads the recorder's clock, the battle's end is the
 * one the recorder saw since the chain started).
 */
class ChainRunnerForAgentTest {

    private val overworld = GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, null)

    private fun recorder() = Recorder(FakeGame(Screen.Overworld(null, Awaiting.INPUT)), every = 1)

    private fun battler(ref: BattlerRef, species: Int, name: String) = BattlerState(
        ref = ref, mon = null, species = Named(SpeciesId(species), name), nickname = null, level = 50, hp = 100, maxHp = 150,
        status = null, volatile = emptySet(), statStages = emptyMap(), types = emptyList(), moves = emptyList(), personality = species.toLong(),
    )

    private val battle = GameState(
        0, Screen.Battle(Awaiting.INPUT), null, emptyList(), null,
        BattleState(BattleKind.TRAINER, false, BattlerRef.PLAYER_LEFT, listOf(battler(BattlerRef.PLAYER_LEFT, 250, "HO-OH"), battler(BattlerRef.FOE_LEFT, 94, "GENGAR")),
            listOf("Leader Morty"), emptyList(), null),
        null,
    )

    @Test
    fun eachStepIsProgressNamedAndToldToTheHost() = runTest {
        val recorder = recorder()
        val told = mutableListOf<Triple<Int, Int, String>>()
        val notes = mutableListOf<String?>()
        val result = ChainRunner.forAgent(
            recorder,
            observe = { overworld },
            execute = { _, _ -> notes += recorder.progress.note; ActionOutcome.Done() },
            onStep = { index, total, action -> told += Triple(index, total, action.key) },
        ).run(listOf(GameAction.Press(Button.A), GameAction.Press(Button.B)))
        assertEquals(listOf("press(a)", "press(b)"), result.performed)
        assertEquals(listOf(Triple(0, 2, "press(a)"), Triple(1, 2, "press(b)")), told)
        // The note is set before the step runs: a remote agent's progress shows which step is running.
        assertEquals(listOf<String?>("step 1/2: press(a)", "step 2/2: press(b)"), notes)
    }

    @Test
    fun aSingleStepIsNamedByItsKeyAlone() = runTest {
        val recorder = recorder()
        ChainRunner.forAgent(recorder, observe = { overworld }, execute = { _, _ -> ActionOutcome.Done() }).run(listOf(GameAction.Press(Button.A)))
        assertEquals("press(a)", recorder.progress.note)
    }

    @Test
    fun theIdleLimitReadsTheRecordersClock() = runTest {
        // Nothing progresses after the first step (a zero idle limit): the next step isn't started.
        val result = ChainRunner.forAgent(
            recorder(), observe = { overworld }, execute = { _, _ -> ActionOutcome.Done() },
            limits = ChainLimits(idle = Duration.ZERO, total = 1.hours),
        ).run(listOf(GameAction.Press(Button.A), GameAction.Press(Button.B)))
        assertEquals(listOf("press(a)"), result.performed)
        assertIs<ChainStop.Idle>(result.stop)
        assertEquals(listOf("press(b)"), result.skipped.map { it.key })
    }

    @Test
    fun theBattleEndIsTheOneTheRecorderSawSinceTheChainStarted() = runTest {
        val recorder = recorder()
        // A battle decided before this chain (another call's) says nothing about this one.
        recorder.log.append { seq -> GameEvent.BattleDecided(seq, 0, BattleOutcome.WON, BattleKind.TRAINER) }
        // The step's own settling ran past the blackout: the state after it shows the team healed, out of battle.
        val healed = battle.copy(screen = Screen.Overworld(null, Awaiting.INPUT), battle = null)
        var current = battle
        val result = ChainRunner.forAgent(
            recorder,
            observe = { current },
            execute = { _, _ ->
                recorder.log.append { seq -> GameEvent.BattleDecided(seq, 1, BattleOutcome.LOST, BattleKind.TRAINER) }
                current = healed
                ActionOutcome.Done()
            },
        ).run(listOf(GameAction.Attack(MoveRef("move:1")), GameAction.Attack(MoveRef("move:1"))))
        assertEquals("BATTLE_LOST", result.droppedBecause?.code)
        assertEquals(1, result.dropped.size)
    }
}
