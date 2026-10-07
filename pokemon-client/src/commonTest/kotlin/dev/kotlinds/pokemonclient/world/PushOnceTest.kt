package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** [PushPlanner.pushOnce]: the walk to a boulder's other side and its one push towards a direction (the `push` action). */
class PushOnceTest {

    private fun area(vararg rows: String): Area {
        val width = rows.maxOf { it.length }
        return Area(1, "test", 0, 0, width, rows.size, Array(width * rows.size) { i ->
            if (rows[i / width].getOrElse(i % width) { '#' } == '.') TileInfo(false, TileKind.Floor, listOf(0)) else TileInfo(true, TileKind.Wall)
        })
    }

    private val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.STRENGTH))

    /** A room with the boulder at 2,2; a second boulder at 4,2. */
    private val room = area(
        "......",
        "......",
        "......",
        "......",
    )
    private val overlay = Overlay(listOf(LiveObject(2, 2, null, clearedBy = FieldMoveKind.STRENGTH), LiveObject(4, 2, null, clearedBy = FieldMoveKind.STRENGTH)))

    @Test
    fun theBoulderIsPushedOnceFromItsOtherSide() {
        val route = assertIs<Route>(PushPlanner(room, overlay).pushOnce(Node(0, 0), options, 2 to 2, Direction.NORTH))
        val push = route.edges.filterIsInstance<PushEdge>().single()
        assertEquals(Triple(2 to 2, 2 to 1, Direction.NORTH), Triple(push.objectFrom, push.objectTo, push.direction))
        // Pushed from the south side (2,3), the player walked round without moving the other boulder.
        assertEquals(2 to 3, push.to.x to push.to.y)
        assertEquals(route.edges.last(), push)
    }

    @Test
    fun aPushAgainstAWallOrAnotherBoulderOrWithoutStrengthHasNoPlan() {
        // East onto 3,2, next to the other boulder: one tile, fine.
        assertIs<Route>(PushPlanner(room, overlay).pushOnce(Node(0, 0), options, 2 to 2, Direction.EAST))
        val corridor = area(
            "#####",
            ".....",
            "#####",
        )
        val boulder = Overlay(listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH)))
        // North of it is a wall, and its south side is a wall too: no plan, nothing would move.
        assertNull(PushPlanner(corridor, boulder).pushOnce(Node(0, 1), options, 2 to 1, Direction.NORTH))
        // West: its east side (3,1) can't be reached from the west without crossing it.
        assertNull(PushPlanner(corridor, boulder).pushOnce(Node(0, 1), options, 2 to 1, Direction.WEST))
        assertIs<Route>(PushPlanner(corridor, boulder).pushOnce(Node(0, 1), options, 2 to 1, Direction.EAST))
        // Into the other boulder: refused.
        val pair = Overlay(listOf(LiveObject(1, 1, null, clearedBy = FieldMoveKind.STRENGTH), LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH)))
        assertNull(PushPlanner(corridor, pair).pushOnce(Node(0, 1), options, 1 to 1, Direction.EAST))
        // Without Strength, never.
        assertNull(PushPlanner(corridor, boulder).pushOnce(Node(0, 1), RouteOptions(), 2 to 1, Direction.EAST))
        // Not a boulder.
        assertNull(PushPlanner(corridor, boulder).pushOnce(Node(0, 1), options, 3 to 1, Direction.EAST))
    }
}
