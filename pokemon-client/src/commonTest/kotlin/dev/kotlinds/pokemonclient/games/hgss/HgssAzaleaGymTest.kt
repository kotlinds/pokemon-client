package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.MechanismPlanner
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PuzzleMechanism
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.SwitchEdge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Azalea Gym's carts and levers as a puzzle the route planner operates ([HgssAzaleaGym]). */
class HgssAzaleaGymTest {

    /** The gym on entry (`InitAzaleaGym`): carts at stations 0, 1, 2 and 7, levers down. */
    private val entry = HgssAzaleaGym(HgssAzaleaGym.Carts(listOf(0, 1, 2, 7), 0))

    @Test
    fun `a station with its cart rides it to the arrival station of the lever state`() {
        // Station 0 (entered from the south tile, 3,32) goes to station 4 (9,24): off one tile north of it.
        val ride = assertNotNull(entry.ride(entry.state, 3, 32))
        assertEquals(HgssAzaleaGym.Carts(listOf(1, 2, 4, 7), 0), ride.state)
        assertEquals(9 to 23, ride.playerX to ride.playerY)
        // No cart at station 3 (its trigger is the station itself): nothing happens.
        assertNull(entry.ride(entry.state, 3, 24))
        // Station 6 has no route with the levers down; with lever 0 flipped it goes to station 9.
        val six = HgssAzaleaGym(HgssAzaleaGym.Carts(listOf(6), 0))
        assertNull(six.ride(six.state, 3, 17))
        assertEquals(listOf(9), six.ride(six.state.copy(switches = 1), 3, 17)?.state?.stations)
    }

    /** Two carts waiting at one station (the save's slots say so) are both kept: riding one leaves the other there. */
    @Test
    fun `two carts at one station are two carts`() {
        val gymmick = ByteArray(12).also {
            it[0] = HgssAzaleaGym.GYMMICK_TYPE.toByte()
            it[4] = 0; it[5] = 0; it[6] = 1; it[7] = 7
        }
        val carts = assertNotNull(HgssAzaleaGym.carts(gymmick))
        assertEquals(listOf(0, 0, 1, 7), carts.stations)
        val gym = HgssAzaleaGym(carts)
        // Station 0 rides to station 4: the other cart still waits at station 0.
        assertEquals(listOf(0, 1, 4, 7), assertNotNull(gym.ride(gym.state, 3, 32)).state.stations)
    }

    @Test
    fun `every lever is pressed from its four sides and toggles its bit`() {
        val presses = entry.presses(entry.state)
        assertEquals(4 * 4, presses.size)
        val fromBelow = presses.single { it.target == "sign:3" && it.facing == Direction.NORTH }
        assertEquals(2 to 19, fromBelow.x to fromBelow.y)
        assertEquals(2, fromBelow.state.switches)
        assertTrue(presses.filter { it.target == "sign:1" }.all { it.state.switches == 1 })
        assertEquals(PuzzleMechanism.CART_RIDE, entry.mechanism)
    }
}

/**
 * The Azalea Gym on real saves (bench, DeSmuME, 16-badge save; skipped without `POKEMON_ROM`): `pz_azalea_entry` on
 * entering the gym (9,37), and `pz_azalea_bugsy` after one `go_to person:5` from there, which rode the carts and
 * pressed sign:0 and sign:3 on the way to Bugsy (9,6). NOTES (map randomizer run, 4:35): `go_to cart:N` was refused
 * and the gym crossed with `step` only.
 */
class HgssAzaleaGymFixtureTest {

    private fun field(name: String): FieldState = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).field)

    @Test
    fun `the puzzle carries the carts and levers for the planner`() {
        val puzzle = assertNotNull(field("pz_azalea_entry").puzzle)
        val gym = assertIs<HgssAzaleaGym>(puzzle.mechanics)
        assertEquals(HgssAzaleaGym.Carts(listOf(0, 1, 2, 7), 0), gym.state)
        assertEquals(listOf("cart:0", "cart:1", "cart:2", "cart:7"), puzzle.teleports.map { it.id })
        // The stations are the puzzle's teleports, not scenes: routes ride them.
        val triggers = MovePlans.puzzleTriggerTiles(puzzle)
        assertTrue((3 to 32) in triggers && (9 to 9) in triggers, triggers.toString())
    }

    @Test
    fun `go_to Bugsy rides the carts and presses the levers, ending as the game did`() {
        val area = assertNotNull(HgssWorldRom.require().areaOf(HgssAzaleaGym.MAP))
        val start = field("pz_azalea_entry")
        assertEquals(9 to 37, start.x to start.y)
        val puzzle = assertNotNull(start.puzzle)
        val gym = assertIs<HgssAzaleaGym>(puzzle.mechanics)
        val overlay = Overlay(
            objects = start.objects.map { LiveObject(it.x, it.y, it.facing, isFollower = it.kind == FieldObjectKind.FOLLOWER) },
            activeTriggers = area.triggers.filter { it.zone == HgssAzaleaGym.MAP }.map { it.x to it.y }.toSet() - MovePlans.puzzleTriggerTiles(puzzle),
            teleports = MovePlans.puzzleTeleports(puzzle),
        )
        // Without the levers no plain route reaches Bugsy.
        val bugsy = start.objects.single { it.id == "person:5" }
        val next = Direction.entries.map { bugsy.x + it.dx to bugsy.y + it.dy }.toSet()
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area, overlay).route(Node(9, 37), RouteOptions()) { (it.x to it.y) in next })
        val route = assertNotNull(MechanismPlanner(area, overlay, gym).route(Node(9, 37), RouteOptions()) { (it.x to it.y) in next })
        assertEquals(listOf("sign:0", "sign:3"), route.edges.filterIsInstance<SwitchEdge>().map { it.target })
        // Replayed with the rules: every ride is what the station does, and the carts end where the game left them.
        var state = gym.state
        for (edge in route.edges) when (edge) {
            is Edge.Teleport -> state = assertNotNull(gym.ride(state, edge.via.x, edge.via.y)).also { assertEquals(edge.to.x to edge.to.y, it.playerX to it.playerY) }.state
            is SwitchEdge -> state = gym.presses(state).single { it.x == edge.to.x && it.y == edge.to.y && it.facing == edge.direction }.state
            else -> Unit
        }
        val end = field("pz_azalea_bugsy")
        assertEquals(route.end!!.x to route.end!!.y, end.x to end.y)
        assertEquals(assertIs<HgssAzaleaGym>(end.puzzle?.mechanics).state, state)
    }
}
