package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PersonRole
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Sign
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WarpTrigger
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import dev.kotlinds.pokemonclient.state.MapName

class ViewsTest {

    private val area = Area(
        id = 1, name = "test", originX = 0, originY = 0, width = 5, height = 3,
        tiles = Array(15) { i -> if (i % 5 == 4) TileInfo(true, TileKind.Wall) else if (i == 2) TileInfo(false, TileKind.TallGrass) else TileInfo(false, TileKind.Floor) },
        warps = listOf(Warp(zone = 7, id = 0, x = 0, y = 2, targetZone = 9, targetWarp = 0, trigger = WarpTrigger.Press(Direction.SOUTH))),
        signs = listOf(Sign(zone = 7, id = 3, x = 3, y = 0, script = 1)),
    )

    private val field = FieldState(
        mapId = 7, mapName = MapName(7, map = "Town"), x = 2, y = 1, height = 0, facing = Direction.NORTH, movement = MovementMode.WALK, moving = false,
        objects = listOf(FieldObject("person:4", "nurse", FieldObjectKind.PERSON, 3, 1, Direction.SOUTH, PersonRole.NURSE)),
    )

    @Test
    fun theMapShowsTilesPeopleExitsAndSignsWithTheirIds() {
        val view = MapView.render(area, field, mapName = { MapName(it, map = if (it == 9) "Route 1" else null) }, width = 5, height = 3)
        val rows = view["map"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("   1 . . A P #", rows[2])
        assertEquals("   0 . . \" S #", rows[1])
        assertEquals("   2 E . . . #", rows[3])
        val exits = view["exits"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue(exits.startsWith("warp:0 at 0,2 (2 west, 1 south) → Route 1"), exits)
        assertTrue("press south" in exits)
        assertEquals("person:4 nurse at 3,1 (1 east) facing south [nurse]", view["people"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("sign:3 at 3,0 (1 east, 1 north)", view["signs"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue("tall grass" in view["legend"]!!.jsonPrimitive.content)
    }

    /** A map from rows of heights in field units (digits), `#` for walls. */
    private fun heights(vararg rows: String) = Area(
        id = 2, name = "heights", originX = 0, originY = 0, width = rows[0].length, height = rows.size,
        tiles = Array(rows[0].length * rows.size) { i ->
            val c = rows[i / rows[0].length][i % rows[0].length]
            if (c == '#') TileInfo(true, TileKind.Wall) else TileInfo(false, TileKind.Floor, listOf(c.digitToInt(36) * 8))
        },
    )

    /** The `levels` rows of [area] (the window is the whole area, centered on the player at [x], [y]), without the y column. */
    private fun levels(area: Area, x: Int, y: Int, height: Int) =
        MapView.render(area, field.copy(x = x, y = y, height = height), width = area.width, height = area.height)["levels"]?.jsonArray?.map { it.jsonPrimitive.content.substring(5) }

    /**
     * NOTES (map randomizer run, Whirl Islands B2F): the walkways slope from 0 to 24 and the void beside them, drawn like
     * floor, is at 0: `levels` only appeared now and then. Levels are what is walked between: a slope is one level
     * whatever its heights, the void beside it another one.
     */
    @Test
    fun levelsAreWhatIsWalkedBetween() {
        // A walkway sloping up east (heights 0, 1, 3, 5, 6) beside the void (0) under its high end; the player in the
        // void (the window is centered on them).
        val slope = heights("01356", "##000")
        assertEquals(listOf("1 1 1 1 1", "    0 0 0"), levels(slope, 2, 1, 0))
        // Only a slope: one level, no `levels`.
        assertEquals(null, levels(heights("01356"), 2, 0, 3))
        // Rooms at about the same height split by walls: one level; a walkway behind a wall: another one.
        assertEquals(null, levels(heights("0#0#1"), 2, 0, 0))
        assertEquals(listOf("0   0   1"), levels(heights("0#0#9"), 2, 0, 0))
    }

    /** A warp nothing takes (its tile has no warp behaviour: only where the other side arrives) is told as such. */
    @Test
    fun anArrivalOnlyWarpIsToldAsSuch() {
        val arrival = Area(
            id = 1, name = "test", originX = 0, originY = 0, width = 5, height = 3, tiles = Array(15) { TileInfo(false, TileKind.Floor) },
            warps = listOf(area.warps.single().copy(trigger = WarpTrigger.Never)),
        )
        val view = MapView.render(arrival, field, mapName = { MapName(it, map = if (it == 9) "Route 1" else null) }, width = 5, height = 3)
        val exits = view["exits"]!!.jsonArray.single().jsonPrimitive.content
        assertTrue(exits.endsWith("(an arrival point only: it can't be taken from here)"), exits)
    }

    @Test
    fun relativePositions() {
        assertEquals("here", MapView.relative(field, 2, 1))
        assertEquals("4 west, 2 south", MapView.relative(field, -2, 3))
    }

    @Test
    fun screensAreDescribedWithIdsAndTheHighlightedEntry() {
        val menu = Screen.YesNo("Save?", listOf(Entry("option:yes", "YES"), Entry("option:no", "NO", touch = null)), Cursor.At(1), Topology.vertical(2))
        val json = StateView.screen(menu)
        assertEquals("option:no", json["highlighted"]!!.jsonPrimitive.content)
        assertEquals("Save?", json["question"]!!.jsonPrimitive.content)
        val dialogue = StateView.screen(Screen.Dialogue(TextSource.FIELD, "nurse", "Hello", Awaiting.INPUT))
        assertEquals("nurse", dialogue["speaker"]!!.jsonPrimitive.content)
        val state = StateView.state(GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field))
        assertTrue(state.isNotEmpty())
    }

    @Test
    fun theRepelCounterIsShownWhileARepelWorks() {
        fun view(steps: Int?) = StateView.state(GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field.copy(repelSteps = steps)))
        assertEquals(137, view(137)["repel_steps"]!!.jsonPrimitive.content.toInt())
        assertTrue("repel_steps" !in view(0))
        assertTrue("repel_steps" !in view(null))
    }
}
