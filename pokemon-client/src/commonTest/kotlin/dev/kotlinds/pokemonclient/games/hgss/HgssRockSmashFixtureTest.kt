package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.world.StepWeights
import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.MoveOptions
import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveEdge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Rock Smash on the 16-badge save (bench, DeSmuME; skipped without `POKEMON_ROM`): HM06 from the man of Route 36
 * (426,246, scr_seq_R36_008), taught to Snorlax; Violet City (zone 73) at 503,262, facing north the cracked rock at
 * 503,261 that closes the pocket of the hidden item at 505,254.
 * - `violet_rock_smash_front`: on the overworld in front of the rock;
 * - `violet_rock_smash_question`: A pressed facing it: "Would you like to use Rock Smash?", YES / NO.
 * Live, `go_to 505,255 on Violet City` from Route 36 smashed it by itself ("used Rock Smash at 503,262").
 */
class HgssRockSmashFixtureTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)

    private fun state(name: String): GameState {
        HgssData.useWorld(HgssWorldRom.require())
        HgssData.useGameData(HgssWorldRom.requireData())
        return game.state(HgssFixtures.load(name))
    }

    private fun live(field: FieldState) = field.objects.map {
        LiveObject(it.x, it.y, it.facing, clearedBy = FieldMoveKind.ROCK_SMASH.takeIf { _ -> it.obstacle == ObstacleKind.SMASH_ROCK })
    }

    @Test
    fun `the route into the pocket smashes the rock when a party member knows Rock Smash`() {
        val state = state("violet_rock_smash_front")
        val field = assertNotNull(state.field)
        assertEquals(Triple(73, 503, 262), Triple(field.mapId, field.x, field.y))
        assertEquals(ObstacleKind.SMASH_ROCK, field.objects.single { it.x == 503 && it.y == 261 }.obstacle)
        val access = FieldMoves.access(state) { game.fieldMoveRule(it) }
        assertEquals("SNORLAX", assertIs<FieldMoveAccess.Usable>(access[FieldMoveKind.ROCK_SMASH]).monName)
        val area = assertNotNull(HgssData.world?.areaOf(field.mapId))
        val pathfinder = Pathfinder(area, Overlay(objects = live(field)))
        val start = Node(field.x, field.y)
        val pocket: (Node) -> Boolean = { it.x == 505 && it.y == 255 }
        val found = assertIs<Pathfinder.Result.Found>(pathfinder.route(start, MovePlans.routeOptions(field, MoveOptions(), FieldMoves.usable(access), StepWeights.NONE), isGoal = pocket))
        val smash = assertIs<FieldMoveEdge>(found.route.edges.first())
        assertEquals(FieldMoveKind.ROCK_SMASH, smash.move)
        assertEquals(Direction.NORTH, smash.direction)
        assertEquals(503 to 261, smash.to.x to smash.to.y)
        assertTrue(smash.clearsObstacle)
        // Without Rock Smash, the failure names the rock and where to use it from.
        val failure = assertIs<Pathfinder.Result.Failed>(pathfinder.route(start, MovePlans.routeOptions(field, MoveOptions(), emptySet(), StepWeights.NONE), isGoal = pocket)).failure
        assertEquals(RouteFailure.NeedsFieldMove(FieldMoveKind.ROCK_SMASH, 503, 261, Node(503, 262), Direction.NORTH), failure)
    }

    @Test
    fun `the Rock Smash question is a YES NO choice read by id`() {
        val screen = assertIs<Screen.YesNo>(state("violet_rock_smash_question").screen)
        assertEquals(listOf("option:yes", "option:no"), screen.entries.map { it.id })
        assertEquals(Cursor.At(0), screen.cursor)
    }
}
