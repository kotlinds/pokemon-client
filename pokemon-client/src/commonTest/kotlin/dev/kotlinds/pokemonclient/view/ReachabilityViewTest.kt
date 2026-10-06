package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.Reachability
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.AreaKind
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the map view adds for reaching its targets: the reachability of each line (only when something is needed),
 * double doors told as one, and the void around a room (drawn apart, and said when the player stands in it).
 */
class ReachabilityViewTest {

    /**
     * A room drawn in ASCII ('#' wall, '.' floor, 'm' an exit mat) inside its block: the rest is floor no event
     * reaches (the void), like a building's 32×32 block around its room.
     */
    private fun room(rows: List<String>, warps: List<Warp>, kind: AreaKind = AreaKind.SINGLE_MAP): Area {
        val width = rows.maxOf { it.length }
        val tiles = Array<TileInfo?>(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { '.' }) {
                '#' -> TileInfo(true, TileKind.Wall)
                'm' -> TileInfo(false, TileKind.Door)
                else -> TileInfo(false, TileKind.Floor)
            }
        }
        return Area(1, "room", 0, 0, width, rows.size, tiles, warps = warps, kind = kind)
    }

    private fun field(x: Int, y: Int, objects: List<FieldObject> = emptyList()) =
        FieldState(1, MapName(1, map = "Room"), x, y, 0, Direction.NORTH, MovementMode.WALK, moving = false, objects = objects)

    /** Two exit mats side by side leading to the same warp of the town: one door, each warp keeping its id. */
    private val doubleDoor = room(
        listOf("#####.", "#...#.", "#mm##.", "######", "......"),
        listOf(Warp(1, 0, 1, 2, 2, 5, WarpTrigger.Press(Direction.SOUTH)), Warp(1, 1, 2, 2, 2, 5, WarpTrigger.Press(Direction.SOUTH))),
    )

    private val town = object : WorldSource {
        override fun areaOf(zoneId: Int) = if (zoneId == 1) doubleDoor else null
    }

    @Test
    fun aDoubleDoorIsTwoIdsToldAsOneDoor() {
        val exits = MapView.render(doubleDoor, field(2, 1), width = 5, height = 3, world = town).getValue("exits").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(exits.any { it.startsWith("warp:0") && "double door with warp:1" in it }, exits.toString())
        assertTrue(exits.any { it.startsWith("warp:1") && "double door with warp:0" in it }, exits.toString())
    }

    @Test
    fun eachLineSaysWhatReachingItNeedsAndOnlyThen() {
        val man = FieldObject("person:3", "gym guide", FieldObjectKind.PERSON, 3, 1, Direction.SOUTH)
        val reach = { id: String ->
            when (id) {
                "warp:0" -> Reachability(requiresFieldMoves = listOf(FieldMoveKind.SURF, FieldMoveKind.STRENGTH), blockedByPerson = man)
                "warp:1" -> Reachability(oneWay = true)
                else -> Reachability.DIRECT
            }
        }
        val view = MapView.render(doubleDoor, field(2, 1, listOf(man)), width = 5, height = 3, world = town, reach = reach)
        val exits = view.getValue("exits").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(exits.single { it.startsWith("warp:0") }.endsWith("[requires_field_moves: [surf, strength]; blocked_by_person: person:3 (gym guide)]"), exits.toString())
        assertTrue(exits.single { it.startsWith("warp:1") }.endsWith("[one_way]"), exits.toString())
        // Nothing added to what a walk reaches.
        val people = view.getValue("people").jsonArray.single().jsonPrimitive.content
        assertTrue(!people.contains("["), people)
        // The fields shown are explained once, the others not.
        val legend = view.getValue(MapView.REACH_LEGEND).jsonPrimitive.content
        assertTrue("requires_field_moves:" in legend && "blocked_by_person:" in legend && "one_way:" in legend, legend)
        assertTrue("requires_intermediate_warp" !in legend, legend)
        assertNull(MapView.render(doubleDoor, field(2, 1), width = 5, height = 3, world = town)[MapView.REACH_LEGEND])
    }

    @Test
    fun theVoidAroundTheRoomIsDrawnApartAndSaidWhenStoodIn() {
        // The room is x 1..3, y 1..2; column 5 and row 4 are floor of the block that no event reaches: the void.
        val inRoom = MapView.render(doubleDoor, field(2, 2), width = 5, height = 5, world = town)
        assertNull(inRoom[MapView.OUTSIDE])
        val rows = inRoom.getValue("map").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(rows.last().endsWith(", , , , ,"), rows.toString())
        assertTrue("outside the map" in inRoom.getValue("legend").jsonPrimitive.content)
        // One tile past the exit mats, on the wall (the exit step of a map-randomized arrival): outside, step north back in.
        val below = MapView.render(doubleDoor, field(1, 3), width = 5, height = 3, world = town)
        assertEquals(
            "you stand outside the walkable area (the game left you past an exit, or you walked off the map): step north back onto 1,2",
            below.getValue(MapView.OUTSIDE).jsonPrimitive.content,
        )
        // Further into the void, nothing leads back.
        val lost = MapView.render(doubleDoor, field(2, 4), width = 5, height = 3, world = town)
        assertTrue("no tile next to you leads back in" in lost.getValue(MapView.OUTSIDE).jsonPrimitive.content)
    }

    /**
     * NOTES (review): Vermilion City's fenced fields were drawn "outside the map". An overworld (maps walked into each
     * other) has no void: the same tiles no event reaches are plain floor there.
     */
    @Test
    fun theOverworldHasNoVoid() {
        val overworld = room(listOf("#####.", "#...#.", "#mm##.", "######", "......"), doubleDoor.warps, AreaKind.OVERWORLD)
        assertTrue((0..5).none { x -> overworld.outside(x, 4) })
        val rows = MapView.render(overworld, field(2, 2), width = 5, height = 5).getValue("map").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(rows.none { ',' in it }, rows.toString())
    }

    /**
     * A floor only a mechanism reaches (a lift's top, a cart's arrival station) is a place, never the void: the live
     * puzzle's teleports, surfaces and platforms keep the tiles connected to them out of it.
     */
    @Test
    fun aFloorOnlyAMechanismReachesIsNoVoid() {
        assertTrue(doubleDoor.outside(5, 4) && doubleDoor.outside(0, 4))
        val puzzle = dev.kotlinds.pokemonclient.state.PuzzleState(
            dev.kotlinds.pokemonclient.state.PuzzleKind.CART_RIDES, "rule",
            teleports = listOf(dev.kotlinds.pokemonclient.state.PuzzleTeleport("cart:0", dev.kotlinds.pokemonclient.state.TeleportKind.CART_RIDE,
                listOf(dev.kotlinds.pokemonclient.state.PuzzleTile(2, 1)), dev.kotlinds.pokemonclient.state.PuzzleTile(1, 4))),
        )
        val void = MapView.voidOf(doubleDoor, field(2, 1).copy(puzzle = puzzle))
        // The landing row (y 4) is reached by the ride: not void; the column behind the wall (x 5, y 0..2) still is.
        assertTrue((0..5).none { x -> void(x, 4) }, "landing region")
        assertTrue(void(5, 0))
    }
}
