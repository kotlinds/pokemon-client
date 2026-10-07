package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The `push` action towards a direction, on any map with Strength boulders (race Claude vs Codex: `push person:3` on
 * Victory Road answered "push isn't possible on this screen", the action only existed for the Ice Path holes). A
 * simulated room ([GridGame]): walking into a boulder slides it one tile when the tile behind is free floor, the
 * player staying where they are, like HGSS.
 */
class PushActionTest {

    /** '.' floor, '#' wall. */
    private class BoulderRoom(rows: List<String>, x: Int, y: Int, val boulders: MutableMap<String, Pair<Int, Int>>, val strength: FieldMoveAccess = FieldMoveAccess.Usable(0, "MACHAMP")) : GridGame(x, y) {
        val area: Area = run {
            val width = rows.maxOf { it.length }
            Area(1, "room", 0, 0, width, rows.size, Array(width * rows.size) { i ->
                if (rows[i / width].getOrElse(i % width) { '#' } == '.') TileInfo(false, TileKind.Floor, listOf(0)) else TileInfo(true, TileKind.Wall)
            })
        }
        override val name = "Room"
        override val world = object : WorldSource {
            override fun areaOf(zoneId: Int) = area.takeIf { zoneId == 1 }
            override val zoneCount get() = 2
        }
        override fun mapName(id: Int) = MapName(id, map = "Room")
        override fun scriptVariable(memory: Memory, id: Int) = 0

        private fun free(x: Int, y: Int) = area.tile(x, y)?.blocked == false && boulders.values.none { it == x to y }

        override fun step(direction: Direction) {
            val nx = x + direction.dx
            val ny = y + direction.dy
            val boulder = boulders.entries.firstOrNull { it.value == nx to ny }
            if (boulder != null) {
                if (strength is FieldMoveAccess.Usable && free(nx + direction.dx, ny + direction.dy)) boulders[boulder.key] = nx + direction.dx to ny + direction.dy
                return
            }
            if (free(nx, ny)) moveTo(nx, ny)
        }

        override fun state(memory: Memory): GameState {
            val objects = boulders.map { (id, at) -> FieldObject(id, "boulder", FieldObjectKind.OBSTACLE, at.first, at.second, null, obstacle = ObstacleKind.BOULDER) }
            val field = FieldState(1, mapName(1), x, y, 0, facing, MovementMode.WALK, moving = false, objects = objects)
            return GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field,
                fieldMoves = mapOf(FieldMoveKind.STRENGTH to strength))
        }
    }

    private val room = listOf(
        "#######",
        "#.....#",
        "#.....#",
        "#.....#",
        "#######",
    )

    @Test
    fun aBoulderIsPushedOneTileFromItsOtherSide() {
        val game = BoulderRoom(room, 1, 1, mutableMapOf("person:1" to (3 to 2)))
        val done = assertIs<ActionOutcome.Done>(RecipeBase.perform(GameAction.Push("person:1", Direction.NORTH), game.context()))
        assertEquals(3 to 1, game.boulders["person:1"], done.detail)
        // Pushed from below it (3,3); the player stays there.
        assertEquals(3 to 3, game.x to game.y)
        assertEquals("pushed person:1 north from 3,2 to 3,1", done.detail)
    }

    @Test
    fun aPushAgainstAWallMovesNothingAndSaysWhy() {
        val game = BoulderRoom(room, 1, 2, mutableMapOf("person:1" to (3 to 1)))
        val failed = assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.Push("person:1", Direction.NORTH), game.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertTrue("can't push person:1 (at 3,1) north" in error.detail && "3,0 can't take it" in error.message, error.message)
        assertEquals(3 to 1, game.boulders["person:1"])
        assertEquals(1 to 2, game.x to game.y)
    }

    @Test
    fun withoutStrengthNothingMoves() {
        val game = BoulderRoom(room, 1, 1, mutableMapOf("person:1" to (3 to 2)), strength = FieldMoveAccess.NoPokemon)
        val failed = assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.Push("person:1", Direction.NORTH), game.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertEquals(UnavailableReason.NO_POKEMON_KNOWS_MOVE to "No Pokémon of the party knows Strength", error.reason to error.detail)
        assertEquals(1 to 1, game.x to game.y)
    }

    /**
     * Strength missing on the game's side says so with the game's reason, its message saying the same: never "no
     * Pokémon knows it" (the reason used to be NO_POKEMON_KNOWS_MOVE with "Strength can't be used in this game").
     */
    @Test
    fun strengthMissingOnTheGamesSideIsTheGamesReason() {
        val cases = mapOf(
            FieldMoveAccess.NotInGame to "This game has no Strength",
            FieldMoveAccess.Unknown to "using Strength isn't supported in this game yet (its field move rules aren't known)",
            FieldMoveAccess.NotSupported to "using Strength isn't supported in this game yet",
        )
        for ((access, detail) in cases) {
            val game = BoulderRoom(room, 1, 1, mutableMapOf("person:1" to (3 to 2)), strength = access)
            val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.Push("person:1", Direction.NORTH), game.context())).error)
            assertEquals(UnavailableReason.NOT_SUPPORTED_BY_GAME to detail, error.reason to error.detail, "$access")
            assertEquals(1 to 1, game.x to game.y, "nothing pressed for $access")
        }
        // The party's side keeps its reason: a badge missing.
        val badge = BoulderRoom(room, 1, 1, mutableMapOf("person:1" to (3 to 2)), strength = FieldMoveAccess.NoBadge("Plain"))
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.Push("person:1", Direction.NORTH), badge.context())).error)
        assertEquals(UnavailableReason.NEEDS_BADGE to "Strength needs the Plain Badge", error.reason to error.detail)
    }

    /**
     * A game without Strength doesn't have `push` (never listed, refused as NOT_SUPPORTED_BY_GAME on any screen); a
     * game whose rule is only unknown keeps the common rule (listed where boulders are).
     */
    @Test
    fun pushIsNotInAGameWithoutStrength() {
        val without = BoulderRoom(room, 1, 1, mutableMapOf("person:1" to (3 to 2)), strength = FieldMoveAccess.NotInGame)
        assertEquals(Availability.NotInThisGame("This game has no Strength"), PuzzleActions.push.availability(without.recipes, without.context().state()))
        val refused = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(
            ActionRegistry.of().execute(GameAction.Push("person:1", Direction.NORTH), without.context().scope, without)).error)
        assertEquals(UnavailableReason.NOT_SUPPORTED_BY_GAME to "This game has no Strength", refused.reason to refused.detail)
        val unknown = BoulderRoom(room, 1, 1, mutableMapOf("person:1" to (3 to 2)), strength = FieldMoveAccess.Unknown)
        assertIs<Availability.Available>(PuzzleActions.push.availability(unknown.recipes, unknown.context().state()))
    }

    @Test
    fun withoutAHoleTheDirectionIsAsked() {
        val game = BoulderRoom(room, 1, 1, mutableMapOf("person:1" to (3 to 2)))
        val failed = assertIs<ActionOutcome.Failed>(RecipeBase.perform(GameAction.Push("person:1"), game.context()))
        assertEquals("direction", assertIs<ActionError.InvalidParameter>(failed.error).parameter)
    }

    @Test
    fun theActionListsTheBouldersOfTheMapAndParsesTheDirection() {
        val game = BoulderRoom(room, 1, 1, mutableMapOf("person:1" to (3 to 2)))
        val available = assertIs<Availability.Available>(PuzzleActions.push.availability(game.recipes, game.context().state()))
        assertEquals(listOf("person:1"), available.choices.getValue("boulder").map { it.value })
        val parsed = PuzzleActions.push.spec.parse(buildJsonObject { put("boulder", JsonPrimitive("person:1")); put("direction", JsonPrimitive("west")) })
        assertEquals(GameAction.Push("person:1", Direction.WEST), parsed)
        // No boulder on the map: not listed.
        val empty = BoulderRoom(room, 1, 1, mutableMapOf())
        assertEquals(Availability.Hidden, PuzzleActions.push.availability(empty.recipes, empty.context().state()))
    }
}
