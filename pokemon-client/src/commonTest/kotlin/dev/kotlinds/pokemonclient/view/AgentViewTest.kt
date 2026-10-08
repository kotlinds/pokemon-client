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

    /** A trainer battle with the foe [personality] on the field ([hp] left), the battle style [style]. */
    private fun trainerBattle(personality: Long, style: dev.kotlinds.pokemonclient.state.BattleStyle, hp: Int = 100): GameState {
        val foe = dev.kotlinds.pokemonclient.state.BattlerState(
            dev.kotlinds.pokemonclient.state.BattlerRef.FOE_LEFT, null, dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.SpeciesId(149), "DRAGONITE"),
            null, 50, hp, 100, null, emptySet(), emptyMap(), listOf("Dragon"), emptyList(), personality = personality, partySlot = personality.toInt(),
        )
        val battle = dev.kotlinds.pokemonclient.state.BattleState(dev.kotlinds.pokemonclient.state.BattleKind.TRAINER, false, null, listOf(foe), listOf("Lance"), emptyList(), null)
        return GameState(0, Screen.Battle(Awaiting.ANIMATION), null, emptyList(), null, battle, null,
            options = dev.kotlinds.pokemonclient.state.GameOptions(dev.kotlinds.pokemonclient.state.TextSpeed.FAST, false, style))
    }

    @Test
    fun theSetBattleStyleIsRecalledWhenTheFoeSendsItsNextPokemon() {
        // Race: both agents set SET to skip the switch question and never went back for Lance.
        val set = dev.kotlinds.pokemonclient.state.BattleStyle.SET
        val view = AgentView(FakeGame(overworld))
        assertNull(view.describe(trainerBattle(1, set), emptyList(), AgentOptions(knowledge = KnowledgeLevel.NONE), AgentView.Detail.COMPACT)["battle_style"])
        // Knocked out between the two views (the recorder saw its HP reach 0), then the next one sent in.
        val fainted = listOf(dev.kotlinds.pokemonclient.state.GameEvent.FoeFainted(1, 10, dev.kotlinds.pokemonclient.state.BattlerRef.FOE_LEFT))
        val next = view.describe(trainerBattle(2, set), fainted, AgentOptions(knowledge = KnowledgeLevel.NONE), AgentView.Detail.COMPACT)
        assertEquals(AgentView.SET_STYLE_HINT, next["battle_style"]!!.jsonPrimitive.content)
        assertNull(view.describe(trainerBattle(2, set), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)["battle_style"], "once, when it comes in")
        // Seen fainted by a view, then replaced.
        view.describe(trainerBattle(2, set, hp = 0), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)
        assertEquals(AgentView.SET_STYLE_HINT, view.describe(trainerBattle(3, set), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)["battle_style"]!!.jsonPrimitive.content)
    }

    @Test
    fun aFoeSwitchingByItselfIsNoReasonForTheSetReminder() {
        // Review impl13 B8: the trainer's AI switching its Pokémon (no knock-out) asks nothing in either style.
        val set = dev.kotlinds.pokemonclient.state.BattleStyle.SET
        val view = AgentView(FakeGame(overworld))
        view.describe(trainerBattle(1, set), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)
        assertNull(view.describe(trainerBattle(2, set), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)["battle_style"])
        // A faint at another position doesn't count for this one.
        val elsewhere = listOf(dev.kotlinds.pokemonclient.state.GameEvent.FoeFainted(1, 10, dev.kotlinds.pokemonclient.state.BattlerRef.FOE_RIGHT))
        assertNull(view.describe(trainerBattle(3, set), elsewhere, AgentOptions(), AgentView.Detail.COMPACT)["battle_style"])
    }

    @Test
    fun theShiftBattleStyleNeedsNoReminder() {
        val shift = dev.kotlinds.pokemonclient.state.BattleStyle.SHIFT
        val view = AgentView(FakeGame(overworld))
        view.describe(trainerBattle(1, shift, hp = 0), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)
        assertNull(view.describe(trainerBattle(2, shift), emptyList(), AgentOptions(), AgentView.Detail.COMPACT)["battle_style"])
    }

    /**
     * What an agent reads in Platinum's bedroom (a RAM fixture, no ROM): `tune_radio` (no Pokégear in this game) is in
     * neither list, while Fly (in the game, not supported yet) keeps being listed unavailable when the field moves are
     * known. The same JSON keys as in every game.
     */
    @Test
    fun anActionTheGameDoesntHaveIsNeitherOfferedNorListedUnavailable() {
        val platinum = dev.kotlinds.pokemonclient.games.platinum.PlatinumGame(dev.kotlinds.pokemonclient.games.platinum.PlatinumVersion.PLATINUM_US)
        val bedroom = platinum.state(dev.kotlinds.pokemonclient.games.platinum.PlatinumFixtures.load("pt_bedroom"))
        val json = AgentView(platinum).describe(bedroom, emptyList(), AgentOptions(), AgentView.Detail.STANDARD)
        val offered = json["actions"]!!.jsonArray.map { it.toString() }
        val unavailable = json["unavailable"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertTrue(offered.none { "tune_radio" in it }, offered.toString())
        assertTrue(unavailable.none { it.startsWith("tune_radio") }, unavailable.toString())
    }
}
