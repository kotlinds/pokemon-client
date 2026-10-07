package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What blocks a way, as the pathfinder plans and diagnoses it. Rows drawn with explicit heights: '.' floor at 0, 'H'
 * floor at 40 (a cliff), '~' water at 0, 'W' a door at 0 (warp taken on entering), 'b' a door under a bridge (the door
 * at 0, the bridge deck at 40), '=' the bridge deck at 40, 'B' floor under the deck (0 and 40), '#' wall.
 */
private fun area(vararg rows: String): Area {
    val width = rows.maxOf { it.length }
    val warps = mutableListOf<Warp>()
    val tiles = Array<TileInfo?>(width * rows.size) { i ->
        val x = i % width
        val y = i / width
        when (rows[y].getOrElse(x) { '#' }) {
            '.' -> TileInfo(false, TileKind.Floor, listOf(0))
            'H' -> TileInfo(false, TileKind.Floor, listOf(40))
            '=' -> TileInfo(false, TileKind.Floor, listOf(40))
            'B' -> TileInfo(false, TileKind.Floor, listOf(0, 40))
            '~' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true), listOf(0))
            'W' -> TileInfo(false, TileKind.Door, listOf(0)).also { warps += Warp(0, warps.size, x, y, 1, 0) }
            'b' -> TileInfo(false, TileKind.Door, listOf(0, 40)).also { warps += Warp(0, warps.size, x, y, 1, 0) }
            else -> TileInfo(true, TileKind.Wall)
        }
    }
    return Area(0, "test", 0, 0, width, rows.size, tiles, warps = warps)
}

class PathfinderBlockersTest {

    private val surf = RouteOptions(canSurf = true)

    /**
     * NOTES-run-map-randomizer (Route 47, `go_to warp:2`): the planner surfed from 133,394, a bridge at height 160
     * over the sea at 8; the game "didn't offer Surf". Surf starts only from a tile at the water's level.
     */
    @Test
    fun surfIsNeverPlannedFromATileAboveTheWater() {
        val cliff = Pathfinder(area("H~~."))
        assertIs<Pathfinder.Result.Failed>(cliff.route(Node(0, 0), surf, emptySet()) { it.x == 3 && it.y == 0 })
    }

    /** The case that must keep working: from a shore at the water's level, Surf is planned. */
    @Test
    fun surfIsPlannedFromAShoreAtTheWatersLevel() {
        val shore = Pathfinder(area(".~~."))
        val found = assertIs<Pathfinder.Result.Found>(shore.route(Node(0, 0), surf, emptySet()) { it.x == 3 && it.y == 0 })
        assertTrue(found.route.edges.any { it is FieldMoveEdge && it.move == FieldMoveKind.SURF }, found.route.edges.toString())
    }

    /**
     * A goal tile on the water (a neighbouring map's first tiles past the shore) is no plain step without Surf: the
     * game refuses it (Cianwood's `exit:east`, "invisible wall"); the failure names Surf. With Surf, it is surfed.
     */
    @Test
    fun aGoalOnTheWaterNeedsSurf() {
        val map = Pathfinder(area("..~"))
        val failed = assertIs<Pathfinder.Result.Failed>(map.route(Node(0, 0), RouteOptions(), setOf(2 to 0)) { it.x == 2 })
        assertEquals(FieldMoveKind.SURF, assertIs<RouteFailure.NeedsFieldMove>(failed.failure).move)
        val found = assertIs<Pathfinder.Result.Found>(map.route(Node(0, 0), surf, setOf(2 to 0)) { it.x == 2 })
        assertTrue(found.route.edges.last() is FieldMoveEdge, found.route.edges.toString())
    }

    /**
     * Race notes: a story person standing on the warp asked for (the Goldenrod Underground's barricade). No route
     * enters it; the diagnosis names them ([RouteFailure.BlockedByPerson]) before any step.
     */
    @Test
    fun aWarpSomeoneStandsOnIsNotEnteredAndTheyAreNamed() {
        val map = area("....W")
        val blocked = Pathfinder(map, Overlay(objects = listOf(LiveObject(4, 0, Direction.SOUTH))))
        val failed = assertIs<Pathfinder.Result.Failed>(blocked.route(Node(0, 0), RouteOptions(), setOf(4 to 0)) { it.x == 4 && it.y == 0 })
        assertEquals(RouteFailure.BlockedByPerson(4, 0), failed.failure)
        assertEquals(4 to 0, failed.blockers.person)
        // The follower walking behind the player is never in the way.
        val follower = Pathfinder(map, Overlay(objects = listOf(LiveObject(4, 0, Direction.SOUTH, isFollower = true))))
        assertIs<Pathfinder.Result.Found>(follower.route(Node(0, 0), RouteOptions(), setOf(4 to 0)) { it.x == 4 && it.y == 0 })
    }

    /** The case that must keep working: a warp with nobody on it is entered (someone next to it doesn't matter). */
    @Test
    fun aFreeWarpIsEntered() {
        val free = Pathfinder(area("....W", "....."), Overlay(objects = listOf(LiveObject(4, 1, Direction.NORTH))))
        val found = assertIs<Pathfinder.Result.Found>(free.route(Node(0, 0), RouteOptions(), setOf(4 to 0)) { it.x == 4 && it.y == 0 })
        assertEquals(Node(4, 0), found.route.end)
    }

    /**
     * Race notes (Olivine Lighthouse 3F, "walls, heights" with a trainer in the way): a person in a way that also
     * needs another level is named with the level, never "not connected".
     */
    @Test
    fun aPersonInAWayThatAlsoChangesLevelIsNamedWithTheLevel() {
        val map = area("...H.")
        val start = Node(0, 0)
        val goal = { n: Node -> n.x == 4 && n.y == 0 }
        val guarded = Pathfinder(map, Overlay(objects = listOf(LiveObject(1, 0, Direction.SOUTH))))
        val failed = assertIs<Pathfinder.Result.Failed>(guarded.route(start, RouteOptions(), emptySet(), isGoal = goal))
        assertEquals(RouteFailure.DifferentLevel, failed.failure)
        assertEquals(1 to 0, failed.blockers.person)
        // Without the person: another level only (nobody named).
        val clear = assertIs<Pathfinder.Result.Failed>(Pathfinder(map).route(start, RouteOptions(), emptySet(), isGoal = goal))
        assertEquals(RouteFailure.DifferentLevel, clear.failure)
        assertNull(clear.blockers.person)
        // Walls only: still not connected.
        assertEquals(RouteFailure.Unreachable, assertIs<Pathfinder.Result.Failed>(Pathfinder(area("..#..")).route(start, RouteOptions(), emptySet()) { it.x == 4 }).failure)
    }

    /**
     * NOTES-run-map-randomizer (Route 47's warp:1 under a bridge): the door's level is the one its ground leads to
     * ([WorldLinks.warpLevel]): the bridge deck above it isn't.
     */
    @Test
    fun aDoorUnderABridgeIsAtItsGroundsLevel() {
        // The door (0 and 40): the deck passes over it north-south ('B' under it, two surfaces too); its ground is south.
        val map = area(
            "#B#",
            "#b#",
            "#.#",
        )
        assertEquals(0, WorldLinks.warpLevel(map, 1, 1))
        // A tile of one surface: no level to choose.
        assertNull(WorldLinks.warpLevel(area("W."), 0, 0))
    }
}
