package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.FakeGame
import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Blocker
import dev.kotlinds.pokemonclient.state.BlockerCause
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.SceneTrigger
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StoryState
import dev.kotlinds.pokemonclient.state.StoryStep
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What blocks a way is what the player sees on screen (Nathan's decision): `blocked_by` is sent at every knowledge
 * level. Only the walkthrough's reasons (what lifts it, where to go: story knowledge) and the story goals stay gated.
 * Race notes: at the Pokédex level, scene messages said "see blocked_by", a field then sent only with a walkthrough
 * (Claude ×4 at Mahogany's east exit, Lyra ×3 at New Bark).
 */
class BlockedByViewTest {

    private val overworld = Screen.Overworld(null, Awaiting.INPUT)
    private val man = FieldObject("person:2", "man", FieldObjectKind.PERSON, 30, 12, Direction.SOUTH, role = PersonRole.BLOCKER)
    private val story = StoryState(
        goal = StoryStep("johto:radio_tower", "Free the Goldenrod Radio Tower from Team Rocket."),
        blockers = listOf(
            Blocker("trigger:0", "The east exit to Route 44 is closed until Team Rocket is driven out of the Goldenrod Radio Tower.", scene = SceneTrigger(0, 40..40, 10..12, repeats = true)),
            Blocker("person:2", "A man blocks the Gym door: clear the Team Rocket hideout under the souvenir shop first."),
        ),
    )
    private val state = GameState(
        0, overworld, null, emptyList(), null, null,
        FieldState(87, MapName(87, "Mahogany Town"), 35, 11, 0, Direction.EAST, MovementMode.WALK, false, objects = listOf(man)),
        story = story,
    )

    private fun blockedBy(knowledge: KnowledgeLevel, hidden: Boolean = false): List<String>? =
        AgentView(FakeGame(overworld)).describe(state, emptyList(), AgentOptions(knowledge = knowledge, hideDestinations = hidden), AgentView.Detail.STANDARD)[AgentView.BLOCKED_BY]
            ?.jsonArray?.map { it.jsonPrimitive.content }

    @Test
    fun whatBlocksAWayIsSentAtEveryKnowledgeLevel() {
        for (level in KnowledgeLevel.entries) {
            val lines = blockedBy(level) ?: error("no blocked_by at $level")
            assertEquals(2, lines.size, "$level: $lines")
            // The scene trigger: its tiles and that it turns the player back each time.
            assertTrue(lines[0].startsWith("trigger:0 at 40,10..12: stepping there starts a scene that turns you back, again each time"), lines[0])
            // The person: who, where, and to talk to them.
            assertTrue(lines[1].startsWith("person:2 (man) at 30,12: stands in the way until a story event moves them; talk to them"), lines[1])
        }
    }

    @Test
    fun theWalkthroughsReasonsAndGoalsStayGated() {
        for (level in listOf(KnowledgeLevel.NONE, KnowledgeLevel.POKEDEX)) {
            val lines = blockedBy(level)!!
            assertTrue(lines.none { "Radio Tower" in it || "hideout" in it || "Walkthrough" in it }, "$level: $lines")
            assertNull(AgentView(FakeGame(overworld)).describe(state, emptyList(), AgentOptions(knowledge = level), AgentView.Detail.STANDARD)["story_goals"], "$level")
        }
        val walkthrough = blockedBy(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH)!!
        assertTrue("Walkthrough: The east exit to Route 44 is closed" in walkthrough[0], walkthrough[0])
        assertTrue("Walkthrough: A man blocks the Gym door" in walkthrough[1], walkthrough[1])
        // Destinations hidden: the reasons name places, left out; what the player sees stays.
        val hidden = blockedBy(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH, hidden = true)!!
        assertTrue(hidden.all { AgentView.BLOCKER_WHERE_HIDDEN in it && "Route 44" !in it && "hideout" !in it }, hidden.toString())
        assertTrue(hidden[0].startsWith("trigger:0 at 40,10..12"), hidden[0])
    }

    @Test
    fun aScenesAndAPersonsVisibleKindsAreSaid() {
        val once = Blocker("trigger:1", "Your rival waits here.", scene = SceneTrigger(1, 3..3, 4..4, repeats = false))
        val unknown = Blocker("trigger:2", "Stepping here starts a story scene now.", scene = SceneTrigger(2, 5..6, 7..7, repeats = null))
        val electrode = Blocker("person:9", "An Electrode powering the radio transmitter.", BlockerCause.WildPokemon(101))
        val door = Blocker("person:5", "A voice-recognition door.", BlockerCause.PasswordDoor(listOf("person:16"), known = true))
        val lines = listOf(once, unknown, electrode, door).map { AgentView.blockedBy(it, state.field, walkthrough = false, hidden = false) }
        assertEquals("trigger:1 at 3,4: stepping there starts a scene (an event that happens once, then the way is free)", lines[0])
        assertEquals("trigger:2 at 5..6,7: stepping there starts a scene that may stop you or turn you back until the story moves on", lines[1])
        assertTrue("battle it" in lines[2] && "Electrode" !in lines[2], lines[2])
        assertTrue("password" in lines[3] && "you have heard it" in lines[3] && "person:16" !in lines[3], lines[3])
    }

    /** The map legend of a trigger ('x') points to blocked_by only when the view lists a trigger there. */
    @Test
    fun theTriggerLegendPointsToBlockedByOnlyWhenItListsOne() {
        val area = Area(1, "town", 0, 0, 10, 5, Array(50) { TileInfo(false, TileKind.Floor) })
        val field = FieldState(1, MapName(1, map = "town"), 2, 2, 0, Direction.EAST, MovementMode.WALK, false, activeTriggers = setOf(4 to 2))
        val listed = MapView.render(area, field, blockers = setOf("trigger:0"))["legend"]!!.jsonPrimitive.content
        assertTrue("x trigger: stepping here starts a scene or an event now (see blocked_by)" in listed, listed)
        val unlisted = MapView.render(area, field)["legend"]!!.jsonPrimitive.content
        assertTrue("x trigger: stepping here starts a scene or an event now" in unlisted && "blocked_by" !in unlisted, unlisted)
    }

    /**
     * Race notes (Rocket HQ B2F, Claude 16:24): an open door was drawn `P` and counted in the way. Its state is typed
     * ([FieldObject.open]): drawn apart, said on its line, out of the routes' way; a closed one is unchanged.
     */
    @Test
    fun anOpenDoorIsDrawnApartAndStandsInNobodysWay() {
        val area = Area(1, "hq", 0, 0, 10, 5, Array(50) { TileInfo(false, TileKind.Floor) })
        fun door(open: Boolean?) = FieldObject("person:5", "door", FieldObjectKind.PERSON, 4, 2, Direction.SOUTH, role = PersonRole.GATE, open = open)
        fun field(door: FieldObject) = FieldState(1, MapName(1, map = "hq"), 2, 2, 0, Direction.EAST, MovementMode.WALK, false, objects = listOf(door))
        val open = MapView.render(area, field(door(true)))
        val row = open["map"]!!.jsonArray.map { it.jsonPrimitive.content }.single { it.trimStart().startsWith("2 ") }
        assertTrue("D" in row && "P" !in row, row)
        assertTrue("D open door" in open["legend"]!!.jsonPrimitive.content)
        assertTrue(open["people"]!!.jsonArray.single().jsonPrimitive.content.contains("[gate: open]"))
        assertTrue(dev.kotlinds.pokemonclient.actions.MovePlans.overlay(area, field(door(true)), emptySet(), emptySet(), solve = true, onThisMap = false, flags = null).objects.none { it.x == 4 && it.y == 2 })
        // Closed (or unknown): a person in the way, as before.
        val closed = MapView.render(area, field(door(false)))
        assertTrue(closed["people"]!!.jsonArray.single().jsonPrimitive.content.contains("[gate: closed]"))
        assertTrue(dev.kotlinds.pokemonclient.actions.MovePlans.overlay(area, field(door(false)), emptySet(), emptySet(), solve = true, onThisMap = false, flags = null).objects.any { it.x == 4 && it.y == 2 })
        assertTrue(MapView.render(area, field(door(null)))["people"]!!.jsonArray.single().jsonPrimitive.content.contains("[gate]"))
    }

    /** A warp on the tile of an earlier one (the Ruins of Alph's warp:11..14): its line says the first is the one taken. */
    @Test
    fun aWarpOnAnotherWarpsTileSaysWhichOneIsTaken() {
        val warps = listOf(dev.kotlinds.pokemonclient.world.Warp(1, 0, 4, 2, 2, 0), dev.kotlinds.pokemonclient.world.Warp(1, 1, 4, 2, 3, 0, dev.kotlinds.pokemonclient.world.WarpTrigger.Never))
        val area = Area(1, "ruins", 0, 0, 10, 5, Array(50) { TileInfo(false, TileKind.Floor) }, warps = warps)
        val field = FieldState(1, MapName(1, map = "ruins"), 2, 2, 0, Direction.EAST, MovementMode.WALK, false)
        val exits = MapView.render(area, field)["exits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(exits.single { it.startsWith("warp:1 ") }.contains("same tile as warp:0, the warp the game takes there"), exits.toString())
        assertTrue(exits.single { it.startsWith("warp:0 ") }.contains("door with warp:1"), exits.toString())
    }

    @Test
    fun noStoryNoBlockedBy() {
        val none = AgentView(FakeGame(overworld)).describe(state.copy(story = null), emptyList(), AgentOptions(), AgentView.Detail.STANDARD)
        assertFalse(AgentView.BLOCKED_BY in none)
    }
}
