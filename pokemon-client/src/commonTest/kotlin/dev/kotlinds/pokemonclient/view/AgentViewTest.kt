package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.FakeGame
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameEvent
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The agent-facing view every host (the app's sessions, the bench) builds the same way. */
class AgentViewTest {

    private fun field(x: Int) = FieldState(3, MapName(3, "Route 29"), x, 5, 0, Direction.WEST, MovementMode.WALK, false)
    private fun state(x: Int) = GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field(x))
    private val overworld = Screen.Overworld(null, Awaiting.INPUT)

    @Test
    fun theEventsGivenAreToldAsMessagesAndEvents() {
        val view = AgentView(FakeGame(overworld))
        val mon = MonId(1, 2)
        val events = listOf(
            GameEvent.TextShown(1, 10, TextSource.BATTLE, null, "Wild PIDGEY\nfainted!"),
            GameEvent.ScreenChanged(2, 11, "battle", "overworld"),
            GameEvent.LevelUp(3, 12, mon, 9, "CYNDAQUIL"),
        )
        val json = view.describe(state(5), events, AgentOptions(), AgentView.Detail.STANDARD)
        assertEquals(listOf("[battle] Wild PIDGEY fainted!"), json["messages_since_last_call"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("CYNDAQUIL ($mon) reached level 9"), json["events_since_last_call"]!!.jsonArray.map { it.jsonPrimitive.content })
        // One position, the state's, with the map's name first.
        assertEquals("Route 29", (json["position"] as kotlinx.serialization.json.JsonObject)["map"]!!.jsonPrimitive.content)
    }

    @Test
    fun aCompactAnswerSaysTheMapIsUnchangedUntilThePlayerMoves() {
        val view = AgentView(FakeGame(overworld))
        view.describe(state(5), emptyList(), AgentOptions(), AgentView.Detail.STANDARD)
        val still = view.describe(state(5), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)
        assertEquals(AgentView.MAP_UNCHANGED, still["map"]!!.jsonPrimitive.content)
        assertNull(still["bag"])
        val moved = view.describe(state(6), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)
        assertNull(moved["map"], "moved: the map is rendered again (none here: the fake game has no ROM maps)")
    }

    @Test
    fun movementPuzzlesLeftToTheAgentAreSaidInTheFullState() {
        val view = AgentView(FakeGame(overworld))
        val left = view.describe(state(5), emptyList(), AgentOptions(solvePuzzles = false), AgentView.Detail.FULL)
        assertEquals(AgentView.PUZZLES_LEFT_TO_AGENT, left["movement_puzzles"]!!.jsonPrimitive.content)
        assertNull(view.describe(state(5), emptyList(), AgentOptions(), AgentView.Detail.FULL)["movement_puzzles"])
    }

    @Test
    fun theOptionsGiveTheActionsTheSameVisibilityAsTheView() {
        val walkthrough = AgentOptions(knowledge = KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH, solvePuzzles = false, hideDestinations = true)
        assertTrue(walkthrough.actionSettings.revealHidden)
        assertFalse(walkthrough.actionSettings.solvePuzzles)
        assertTrue(walkthrough.actionSettings.hideDestinations)
        assertFalse(AgentOptions(knowledge = KnowledgeLevel.POKEDEX).actionSettings.revealHidden)
    }
}
