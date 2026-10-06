package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Sign
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.TriggerWarp
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.kotlinds.pokemonclient.state.MapName

/**
 * Destinations hidden (`ActionSettings.hideDestinations`): the map view lists every way out of the map with its place
 * but leads it to "unknown" (neither the map's name nor the arrival tile), while everything of the current map stays
 * shown; the state says so.
 */
class HiddenDestinationsViewTest {

    /** Town (zone 7, x 0..2) next to Route 8 (zone 8, x 3..4), a door to zone 9 and a hole to zone 10. */
    private val area = Area(
        id = 1, name = "outdoor", originX = 0, originY = 0, width = 5, height = 3,
        tiles = Array(15) { TileInfo(false, TileKind.Floor) },
        warps = listOf(Warp(zone = 7, id = 3, x = 0, y = 2, targetZone = 9, targetWarp = 0)),
        signs = listOf(Sign(zone = 7, id = 1, x = 2, y = 0, script = 1)),
        zones = IntArray(15) { i -> if (i % 5 < 3) 7 else 8 },
        triggerWarps = listOf(TriggerWarp(zone = 7, trigger = 2, x = 0, y = 0, targetZone = 10, toX = 4, toY = 4)),
    )

    /** The door's arrival tile is known to the world: the visible view names it, the hidden one must not. */
    private val world = object : WorldSource {
        override fun areaOf(zoneId: Int) = when (zoneId) {
            9 -> Area(9, "house", 0, 0, 3, 3, Array(9) { TileInfo(false, TileKind.Floor) }, warps = listOf(Warp(9, 0, 1, 2, 7, 3)))
            else -> area
        }
    }

    private val names = mapOf(7 to "Town", 8 to "Route 8", 9 to "Secret House", 10 to "Cellar")

    private val field = FieldState(
        mapId = 7, mapName = MapName(7, map = "Town"), x = 1, y = 1, height = 0, facing = Direction.NORTH, movement = MovementMode.WALK, moving = false,
        objects = listOf(FieldObject("person:4", "old man", FieldObjectKind.PERSON, 2, 1, Direction.SOUTH)),
    )

    private fun exits(hide: Boolean) = MapView.render(area, field, { MapName(it, map = names[it]) }, width = 5, height = 3, world = world, hideDestinations = hide)

    @Test
    fun hiddenExitsLeadToUnknownAndNameNoOtherMap() {
        val view = exits(hide = true)
        val lines = view["exits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(lines.any { it.startsWith("warp:3 at 0,2 (1 west, 1 south) → unknown") }, lines.toString())
        assertTrue(lines.any { it.startsWith("hole:2 at 0,0 (1 west, 1 north) → unknown (a hole") }, lines.toString())
        assertTrue(lines.any { it.startsWith("exit:east → unknown along 2,0..2") }, lines.toString())
        val text = view.toString()
        listOf("Route 8", "Secret House", "Cellar", "map:8", "map:9", "map:10", "(1,2)", "(4,4)").forEach { leak ->
            assertFalse(leak in text, "$leak leaked: $text")
        }
        // Everything of the current map stays: people, signs, tiles.
        assertEquals("person:4 old man at 2,1 (1 east) facing south", view["people"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("sign:1 at 2,0 (1 east, 1 north)", view["signs"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("   1   . A P .", view["map"]!!.jsonArray[2].jsonPrimitive.content)
    }

    @Test
    fun withDestinationsShownTheExitsNameWhereTheyLead() {
        val lines = exits(hide = false)["exits"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(lines.any { it.startsWith("warp:3 at 0,2 (1 west, 1 south) → Secret House (1,2)") }, lines.toString())
        assertTrue(lines.any { it.startsWith("hole:2 at 0,0 (1 west, 1 north) → Cellar (4,4)") }, lines.toString())
        assertTrue(lines.any { it.startsWith("exit:east → Route 8") }, lines.toString())
    }

    @Test
    fun theStateSaysDestinationsAreHiddenOnlyWhenTheyAre() {
        val state = GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field)
        assertEquals(StateView.DESTINATIONS_HIDDEN, StateView.state(state, hideDestinations = true)["destinations"]?.jsonPrimitive?.content)
        assertTrue("keep your own notes" in StateView.DESTINATIONS_HIDDEN && "read signs and listen to people" in StateView.DESTINATIONS_HIDDEN)
        assertNull(StateView.state(state)["destinations"])
    }

    @Test
    fun theMapYouAreOnIsStillNamedWhenDestinationsAreHidden() {
        // Arriving somewhere is seen like in the game (its name): only where the exits lead is hidden.
        val state = GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field)
        val position = StateView.state(state, hideDestinations = true)["position"]!!.jsonObject
        assertEquals(field.mapName.toString(), position["map"]!!.jsonPrimitive.content)
        assertEquals("map", position.keys.first())
    }
}
