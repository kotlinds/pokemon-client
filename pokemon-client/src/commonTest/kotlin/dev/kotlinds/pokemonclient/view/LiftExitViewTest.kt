package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Elevator
import dev.kotlinds.pokemonclient.world.ElevatorOperator
import dev.kotlinds.pokemonclient.world.ElevatorStop
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A lift's way out (a warp to the dynamic destination, zone 4095) is listed as leading to the floor the lift goes to,
 * with how it's sent there, never as "map:4095" (NOTES race: the Goldenrod Dept. Store's lift, Codex tried
 * `interact sign:0`; the attendant is person:0). Destinations hidden: no floor named.
 */
class LiftExitViewTest {

    private val lift = Area(
        id = 9, name = "lift", originX = 0, originY = 0, width = 3, height = 3,
        tiles = Array(9) { TileInfo(false, TileKind.Floor) },
        warps = listOf(Warp(zone = 9, id = 0, x = 1, y = 2, targetZone = 4095, targetWarp = 256, trigger = WarpTrigger.Press(Direction.SOUTH))),
    )

    private fun world(operator: ElevatorOperator) = object : WorldSource {
        override fun areaOf(zoneId: Int) = if (zoneId == 9) lift else null
        override fun elevatorOf(zoneId: Int) = if (zoneId == 9) Elevator(9, listOf(0), listOf(ElevatorStop(1, 2), ElevatorStop(2, 2)), operator) else null
    }

    private val field = FieldState(9, MapName(9, map = "Lift"), 1, 1, 0, Direction.SOUTH, MovementMode.WALK, moving = false)

    private fun exit(operator: ElevatorOperator, hide: Boolean = false): String {
        val names = mapOf(1 to "Store 1F", 2 to "Store 2F", 9 to "Lift")
        val view = MapView.render(lift, field, { MapName(it, map = names[it]) }, width = 3, height = 3, world = world(operator), hideDestinations = hide)
        return view.getValue("exits").jsonArray.single().jsonPrimitive.content
    }

    @Test
    fun aLiftsWayOutSaysWhereItLeadsAndWhoSendsIt() {
        assertEquals(
            "warp:0 at 1,2 (1 south) → the floor the lift goes to (Store 1F, Store 2F): talk to person:0 (interact person:0) and choose the floor (step on it, then press south)",
            exit(ElevatorOperator.Attendant(0)),
        )
        assertEquals(
            "warp:0 at 1,2 (1 south) → the floor the lift goes to (Store 1F, Store 2F): it rides by itself to the other floor (step on it, then press south)",
            exit(ElevatorOperator.Shuttle),
        )
        assertEquals(
            "warp:0 at 1,2 (1 south) → the floor the lift goes to: read sign:3 (interact sign:3) and choose the floor (step on it, then press south)",
            exit(ElevatorOperator.Panel(3), hide = true),
        )
    }
}
