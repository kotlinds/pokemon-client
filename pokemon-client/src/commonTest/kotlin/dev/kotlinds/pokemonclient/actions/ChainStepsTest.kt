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

    private fun onMap(id: Int, name: String) = GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null,
        dev.kotlinds.pokemonclient.state.FieldState(id, dev.kotlinds.pokemonclient.state.MapName(id, map = name), 5, 5, 0, null,
            dev.kotlinds.pokemonclient.state.MovementMode.WALK, moving = false))

    @Test
    fun aStepNamingAPersonOfTheMapTheChainLeftIsGivenBack() = runTest {
        // NOTES (Claude, Rocket Hideout): `go_to person:6` after a step that changed floors → "Invalid target person:6"
        // (or, worse, another person:6 of the new floor).
        var current = onMap(1, "B2F")
        val executed = mutableListOf<GameAction>()
        val result = ChainRunner(
            observe = { current },
            execute = { action, _ -> executed += action; current = onMap(2, "B3F"); ActionOutcome.Done() },
        ).run(listOf(GameAction.GoTo(null, null, "warp:1"), GameAction.GoTo(null, null, "person:6"), GameAction.Interact("person:6")))
        assertEquals(listOf("go_to(warp:1)"), result.performed)
        val stop = assertIs<ChainStop.TargetOnOtherMap>(result.stop)
        assertEquals("TARGET_ON_OTHER_MAP", stop.code)
        assertEquals("person:6", stop.target)
        assertEquals(listOf("go_to(person:6)", "interact(person:6)"), result.skipped.map { it.key })
        assertEquals(1, executed.size)
    }

    @Test
    fun aChainStayingOnItsMapOrNamingMapsGoesOn() = runTest {
        // Same map all along: person ids are still that map's.
        var current = onMap(1, "B2F")
        val same = ChainRunner(observe = { current }, execute = { _, _ -> ActionOutcome.Done() })
            .run(listOf(GameAction.GoTo(3, 4, null), GameAction.Interact("person:6")))
        assertEquals(listOf("go_to(3,4)", "interact(person:6)"), same.performed)
        assertNull(same.stop)
        // Another map, but the steps name maps, coordinates or a map's own target: nothing pinned.
        val moved = ChainRunner(observe = { current }, execute = { _, _ -> current = onMap(2, "B3F"); ActionOutcome.Done() })
            .run(listOf(GameAction.GoTo(null, null, "warp:1"), GameAction.GoTo(null, null, "Goldenrod City"), GameAction.GoTo(7, 7, null),
                GameAction.GoTo(null, null, "person:2", map = "B3F")))
        assertEquals(4, moved.performed.size)
        assertNull(moved.stop)
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
