package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.world.StepWeights
import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.MoveOptions
import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.ClimbAxis
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveEdge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rock Climb on the 16-badge save (bench, DeSmuME; skipped without `POKEMON_ROM`), on Mt. Silver Cave 3F (zone 464),
 * at the top of its ten-tile north-south wall (13,4..13,13), facing south:
 * - `silver_rock_climb_top`: on the overworld at 13,3, the party (Typhlosion knows Rock Climb) and the Earth Badge;
 * - `silver_rock_climb_question`: A pressed facing the wall: "Would you like to use Rock Climb?", YES / NO;
 * - `silver_rock_climb_used`: YES answered: "TYPHLOSION used Rock Climb!" waits for A;
 * - `silver_rock_climb_climbing`: the cut-in and the climb itself: an animation, not the player's turn (the walk
 *   waits it out before reading where the player landed).
 */
class HgssRockClimbFixtureTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)

    private fun state(name: String): GameState {
        HgssData.useWorld(HgssWorldRom.require())
        HgssData.useGameData(HgssWorldRom.requireData())
        return game.state(HgssFixtures.load(name))
    }

    @Test
    fun `at the top of the 3F wall the route down is one Rock Climb over the whole wall`() {
        val state = state("silver_rock_climb_top")
        val field = assertNotNull(state.field)
        assertEquals(Triple(464, 13, 3), Triple(field.mapId, field.x, field.y))
        assertEquals(Direction.SOUTH, field.facing)
        val access = FieldMoves.access(state) { game.fieldMoveRule(it) }
        assertIs<FieldMoveAccess.Usable>(access[FieldMoveKind.ROCK_CLIMB])
        val usable = FieldMoves.usable(access)
        assertTrue(FieldMoveKind.ROCK_CLIMB in usable)
        val area = assertNotNull(HgssData.world?.areaOf(field.mapId))
        assertEquals(TileKind.RockClimb(ClimbAxis.NORTH_SOUTH), area.tile(13, 4)?.kind)
        val pathfinder = Pathfinder(area)
        val start = Node(field.x, field.y, pathfinder.levelAt(field.x, field.y, field.height * MovePlans.HEIGHT_UNITS))
        val found = assertIs<Pathfinder.Result.Found>(pathfinder.route(start, MovePlans.routeOptions(field, MoveOptions(), usable, StepWeights.NONE)) { it.x == 13 && it.y == 14 })
        val climb = assertIs<FieldMoveEdge>(found.route.edges.single())
        assertEquals(FieldMoveKind.ROCK_CLIMB, climb.move)
        assertEquals(Direction.SOUTH, climb.direction)
        assertEquals((4..14).map { it }, climb.tiles.map { it.y })
        // And back up from the foot.
        val up = assertIs<Pathfinder.Result.Found>(pathfinder.route(Node(13, 14), MovePlans.routeOptions(field, MoveOptions(), usable, StepWeights.NONE)) { it.x == 13 && it.y == 3 })
        assertEquals(Direction.NORTH, assertIs<FieldMoveEdge>(up.route.edges.single()).direction)
    }

    @Test
    fun `the Rock Climb question is a YES NO choice read by id`() {
        val screen = assertIs<Screen.YesNo>(state("silver_rock_climb_question").screen)
        assertEquals(listOf("option:yes", "option:no"), screen.entries.map { it.id })
        assertEquals(Cursor.At(0), screen.cursor)
    }

    @Test
    fun `after YES the move's message waits for A then the climb is an animation`() {
        val used = assertIs<Screen.Dialogue>(state("silver_rock_climb_used").screen)
        assertEquals(Awaiting.INPUT, used.awaiting)
        val climbing = state("silver_rock_climb_climbing")
        val overworld = assertIs<Screen.Overworld>(climbing.screen)
        assertEquals(Awaiting.ANIMATION, overworld.awaiting)
    }
}
