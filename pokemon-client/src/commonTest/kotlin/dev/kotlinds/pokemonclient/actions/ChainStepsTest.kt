package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Chains whose steps are option keys resolved on the screen reached ([ChainStep.ByKey]: our own loop's models), run
 * by the same [ChainRunner] as the MCP's typed steps.
 */
class ChainStepsTest {

    private fun state(screen: Screen) = GameState(0, screen, null, emptyList(), null, null, null)
    private val overworld = state(Screen.Overworld(null, Awaiting.INPUT))
    private val dialogue = state(Screen.Dialogue(dev.kotlinds.pokemonclient.state.TextSource.FIELD, null, "Hello!", Awaiting.INPUT))

    /** The options of a screen, by key, as a model reads them: `a` everywhere, `advance` only on a dialogue. */
    private fun options(state: GameState): Map<String, GameAction> = buildMap {
        put("a", GameAction.Press(Button.A))
        if (state.screen is Screen.Dialogue) put("advance", GameAction.AdvanceDialogue)
    }

    private fun byKey(key: String) = ChainStep.ByKey(key) { options(it)[key] }

    @Test
    fun aKeyIsResolvedOnTheScreenTheChainReached() = runTest {
        // `advance` isn't an option before `a` opens the dialogue: resolved when its turn comes, it is.
        var current = overworld
        val executed = mutableListOf<GameAction>()
        val result = ChainRunner(
            observe = { current },
            execute = { action, _ -> executed += action; current = if (action is GameAction.Press) dialogue else overworld; ActionOutcome.Done() },
        ).runSteps(listOf(ChainStep.Planned(GameAction.Press(Button.A)), byKey("advance")))
        assertEquals(listOf(GameAction.Press(Button.A), GameAction.AdvanceDialogue), executed)
        assertEquals(listOf("press(a)", "advance_dialogue"), result.performed)
        assertNull(result.stop)
    }

    @Test
    fun aKeyNotOfferedWhereTheChainGotStopsItWithTheStepsLeft() = runTest {
        var current = overworld
        val result = ChainRunner(
            observe = { current },
            execute = { _, _ -> current = overworld; ActionOutcome.Done() },
        ).runSteps(listOf(ChainStep.Planned(GameAction.Press(Button.A)), byKey("advance"), byKey("a")))
        assertEquals(listOf("press(a)"), result.performed)
        val stop = assertIs<ChainStop.NotOffered>(result.stop)
        assertEquals("NOT_OFFERED", stop.code)
        assertEquals(listOf("advance", "a"), result.skipped.map { it.key })
        assertNull(result.failed)
    }
}
