package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.games.hgss.HgssWorldRom
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which ways the bicycle rides ([BikeRide.crossesIce]), on the real HeartGold (USA) ROM (skipped without
 * `POKEMON_ROM`). The bicycle doesn't steer on ice: NOTES race, Codex on the bicycle in the Ice Path, "the game refused
 * 0 steps", fine once off it; checked on the bench since (go_to with `bike` from the Blackthorn City entrance: "got
 * off the Bicycle: the way crosses ice", then "walked" on the icy floors and back on the bicycle elsewhere).
 */
class BikeRideTest {

    private fun route(zone: Int, from: Node, to: Pair<Int, Int>): Pair<dev.kotlinds.pokemonclient.world.Area, dev.kotlinds.pokemonclient.world.Route> {
        val area = assertNotNull(HgssWorldRom.require().areaOf(zone))
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(area).route(from, RouteOptions(acceptOneWay = true), setOf(to)) { it.x == to.first && it.y == to.second })
        return area to found.route
    }

    @Test
    fun `the way from the Ice Path's Blackthorn entrance to its ladder down slides on the ice`() {
        // Ice Path 1F (120): from the Blackthorn City entrance (55,40) to the ladder down to B1F (warp:3, 55,20).
        val (area, way) = route(120, Node(55, 39), 55 to 20)
        assertTrue(BikeRide.crossesIce(area, way), way.edges.toString())
    }

    @Test
    fun `a way without ice is ridden`() {
        // Bell Tower 4F (334): from the ladder's top (24,7) to the ladder up to 5F (24,24), floor and ledges only.
        val (area, way) = route(334, Node(24, 7), 24 to 24)
        assertFalse(BikeRide.crossesIce(area, way))
    }

    /**
     * Review impl13 B5: the trip while destinations are hidden asks the same ice check before getting on the bicycle as
     * a trip across maps ([WorldTravel.crossesIce] without a [WorldTravel.Seen]: the walk on this map), so it never
     * rides onto the ice to get off at once.
     */
    @Test
    fun `the walk on this map while destinations are hidden is checked for ice too`() {
        fun crosses(zone: Int, x: Int, y: Int, target: String, height: Int = 0): Boolean {
            val game = RomStanding(zone, x, y, height = height)
            val context = game.context(ActionSettings(hideDestinations = true))
            val state = context.state()
            val goal = WorldTravel.Goal(zone, MovePlans.resolve(game, state, context.settings, target, null, null)!!)
            return WorldTravel.crossesIce(context, game.world, goal, MoveOptions(bike = true), seen = null)
        }
        // Ice Path 1F: from the Blackthorn City entrance to the ladder down (warp:3), over the ice.
        assertTrue(crosses(120, 55, 39, "warp:3"))
        // Bell Tower 4F: from the ladder's top to the ladder up, no ice.
        assertFalse(crosses(334, 24, 7, "warp:6"))
    }
}
