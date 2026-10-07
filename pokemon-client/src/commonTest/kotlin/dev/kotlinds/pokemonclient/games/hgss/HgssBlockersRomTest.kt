package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.actions.WorldTravel
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.FieldMoveEdge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What blocks a way, on the real HeartGold (USA) ROM (skipped without `POKEMON_ROM`): the cases of the map-randomized
 * run and of the race (NOTES-run-map-randomizer, race triage). Zone ids (include/constants/maps.h): Route 47 = 151,
 * Ruins of Alph = 113, Olivine Lighthouse 3F = 222, Team Rocket HQ B2F = 248.
 */
class HgssBlockersRomTest {

    private val world get() = HgssWorldRom.require()

    /** The tile each Surf of [edges] (from [start]) is used from. */
    private fun surfedFrom(start: Node, edges: List<Edge>): List<Pair<Int, Int>> {
        var from = start
        return buildList {
            for (e in edges) {
                if (e is FieldMoveEdge && e.move == FieldMoveKind.SURF) add(from.x to from.y)
                from = e.to
            }
        }
    }

    /**
     * Route 47 (NOTES-run-map-randomizer, 8:38-8:53, `go_to warp:2`): 133,394 is the end of a bridge at height 160,
     * the sea next to it at 8; the planner surfed from there and the game "didn't offer Surf". From the bridge, the
     * way to the water never surfs from it; from the beach at the water's level (131,393, height 16), Surf is planned.
     */
    @Test
    fun `Route 47 is surfed from the beach, never from the bridge above the sea`() {
        val r47 = assertNotNull(world.areaOf(ROUTE_47))
        assertEquals(listOf(160), r47.tile(133, 394)?.heights)
        assertEquals(listOf(8), r47.tile(132, 394)?.heights)
        val pathfinder = Pathfinder(r47)
        val options = RouteOptions(canSurf = true)
        val bridge = Node(133, 394, 0)
        val water = { n: Node -> n.x == 129 && n.y == 395 }
        (pathfinder.route(bridge, options, emptySet(), isGoal = water) as? Pathfinder.Result.Found)?.let { found ->
            assertTrue((133 to 394) !in surfedFrom(bridge, found.route.edges), found.route.edges.toString())
        }
        val beach = Node(131, 393, 0)
        val found = assertIs<Pathfinder.Result.Found>(pathfinder.route(beach, options, emptySet(), isGoal = water))
        assertTrue(surfedFrom(beach, found.route.edges).isNotEmpty(), found.route.edges.toString())
        assertTrue(surfedFrom(beach, found.route.edges).all { (x, y) -> r47.tile(x, y)!!.heights.any { it <= 8 + RouteOptions.DEFAULT_MAX_CLIMB } })
    }

    /**
     * Route 47's warp:1 (130,385) is a door under a bridge: the door at height 160, the deck at 208 over it. Its
     * level is the ground's ([WorldLinks.warpLevel]); the walks and the view's reachability only reach it there, not
     * from the deck above (NOTES-run-map-randomizer: no reachability label, then `requires_intermediate_warp` after
     * trying). The door entered from the ground still is.
     */
    @Test
    fun `Route 47's door under the bridge is reached from the ground, not from the deck`() {
        val r47 = assertNotNull(world.areaOf(ROUTE_47))
        assertEquals(listOf(160, 208), r47.tile(130, 385)?.heights)
        assertEquals(0, WorldLinks.warpLevel(r47, 130, 385))
        val isGoal = assertNotNull(MovePlans.atWarpLevel(r47, 130, 385))
        val pathfinder = Pathfinder(r47)
        val fromGround = assertIs<Pathfinder.Result.Found>(pathfinder.route(Node(130, 388, 0), RouteOptions(), setOf(130 to 385), isGoal = isGoal))
        assertEquals(Node(130, 385, 0), fromGround.route.end)
        // From the deck: never "arrived" on the deck's surface of the door tile.
        (pathfinder.route(Node(129, 383, 0), RouteOptions(), setOf(130 to 385), isGoal = isGoal) as? Pathfinder.Result.Found)?.let {
            assertEquals(Node(130, 385, 0), it.route.end)
        }
        // The other doors of the ROM on a tile of two surfaces get the level of their ground too.
        assertEquals(0, WorldLinks.warpLevel(r47, 131, 391))
    }

    /**
     * Ruins of Alph (NOTES-run-map-randomizer, 5:45-5:52): warp:11..14 lie on the tiles of warp:8 / warp:9; the game
     * takes the first warp of a tile (Field_GetWarpEventAtXYPos), so they are never taken ([WarpTrigger.Never]) and
     * shown as one doorway with them ([WorldLinks.sameDoors]). Route 47's warp:5 and warp:6 on warp:4's tile too.
     */
    @Test
    fun `warps sharing a tile are one doorway, only the first is taken`() {
        val w = world
        val alph = assertNotNull(w.areaOf(RUINS_OF_ALPH))
        val warps = alph.warps.filter { it.zone == RUINS_OF_ALPH }.associateBy { it.id }
        assertEquals(WarpTrigger.Enter, warps.getValue(8).trigger)
        assertEquals(WarpTrigger.Enter, warps.getValue(9).trigger)
        (11..14).forEach { assertEquals(WarpTrigger.Never, warps.getValue(it).trigger, "warp:$it") }
        val doors = WorldLinks.sameDoors(w, alph, RUINS_OF_ALPH)
        assertEquals(listOf(9, 11, 12, 13, 14), doors[8])
        // The way through that doorway goes where warp:8 leads, as in the game.
        val router = WorldRouter(w)
        val route = assertNotNull(router.route(RUINS_OF_ALPH, Node(430, 286), RouteOptions()) { it.zone == warps.getValue(8).targetZone })
        assertTrue(route.links.first().id in setOf("warp:8", "warp:9"), route.links.toString())
        val r47 = assertNotNull(w.areaOf(ROUTE_47)).warps.filter { it.zone == ROUTE_47 }.associateBy { it.id }
        assertEquals(listOf(WarpTrigger.Never, WarpTrigger.Never), listOf(r47.getValue(5).trigger, r47.getValue(6).trigger))
    }

    /**
     * Team Rocket HQ B2F (race notes, Claude 16:24): the transmitter room's door slides west onto 29,22 (a wall tile)
     * when it opens; it was drawn as a person and counted in the way. Its state is typed now.
     */
    @Test
    fun `the Rocket HQ door is open once slid aside`() {
        assertEquals(true, HgssBlockers.doorOpen(ROCKET_HQ_B2F, 5, 29, 22))
        assertEquals(false, HgssBlockers.doorOpen(ROCKET_HQ_B2F, 5, 31, 22))
        assertNull(HgssBlockers.doorOpen(ROCKET_HQ_B2F, 0, 15, 24), "not a door")
        val hq = assertNotNull(world.areaOf(ROCKET_HQ_B2F))
        assertEquals(TileKind.Wall, hq.tile(29, 22)?.kind, "where the open door stands")
    }

    /**
     * Olivine Lighthouse 3F (race notes, Claude 15:01: `go_to warp:0` from 8,18 → "not connected (walls, heights)",
     * his notes blaming Sailor Kent). The cause isn't the trainers: warp:0 (13,7) is in a room of its own on this
     * floor (reached from 2F or from the 4F hall), with or without anyone standing on the floor the diagnosis is the
     * same. (The way back there goes through the lighthouse's elevator, a link the routes don't know yet.)
     */
    @Test
    fun `Olivine Lighthouse 3F's ladder down isn't kept away by its trainers`() {
        val lh = assertNotNull(world.areaOf(LIGHTHOUSE_3F))
        val people = lh.people.filter { it.zone == LIGHTHOUSE_3F && it.hiddenByFlag == 0 }.map { LiveObject(it.x, it.y, it.facing, sightRange = it.sightRange) }
        val goal = { n: Node -> n.x == 13 && n.y == 7 }
        val start = Node(8, 18)
        val withPeople = assertIs<Pathfinder.Result.Failed>(Pathfinder(lh, Overlay(objects = people)).route(start, RouteOptions(), setOf(13 to 7), isGoal = goal))
        val without = assertIs<Pathfinder.Result.Failed>(Pathfinder(lh).route(start, RouteOptions(), setOf(13 to 7), isGoal = goal))
        assertEquals(RouteFailure.Unreachable, without.failure)
        assertEquals(without, withPeople)
    }

    /**
     * Cianwood's east edge (NOTES-run-map-randomizer 5:48, destinations hidden, no Surf yet): `exit:east`'s first tiles
     * past the edge are the sea at x 192 (Route 41). The walk stepped onto them as goal tiles and the game refused
     * ("invisible wall" at 192,349..354, TIMEOUT); without Surf it is now "needs Surf", and with Surf it is surfed.
     */
    @Test
    fun `Cianwood's east exit over the sea needs Surf`() {
        val cianwood = assertNotNull(world.areaOf(CIANWOOD))
        assertEquals(ROUTE_41, cianwood.zoneAt(192, 350))
        val exit = WorldTravel.exitTarget("exit:east", WorldLinks.connections(cianwood, CIANWOOD).filter { it.id == "exit:east" }, onThisMap = true)
        val goals = MovePlans.goalTiles(cianwood, exit)
        assertTrue((192 to 350) in goals)
        val pathfinder = Pathfinder(cianwood, Overlay(zone = CIANWOOD))
        val beach = Node(190, 350)
        val failed = assertIs<Pathfinder.Result.Failed>(pathfinder.route(beach, RouteOptions(), goals, isGoal = exit.isGoal!!))
        assertEquals(FieldMoveKind.SURF, assertIs<RouteFailure.NeedsFieldMove>(failed.failure).move)
        val surfed = assertIs<Pathfinder.Result.Found>(pathfinder.route(beach, RouteOptions(canSurf = true), goals, isGoal = exit.isGoal!!))
        assertTrue(surfedFrom(beach, surfed.route.edges).isNotEmpty(), surfed.route.edges.toString())
    }

    /**
     * Mahogany Town (NOTES-run-map-randomizer 6:24, race: Claude ×4): the Rage Candy Bar seller's scene (trigger:0,
     * 540,176..177, the seller himself on 540,175: the whole passage) turns the player back from the east exit until
     * the Radio Tower is freed. The exit's line names it
     * (`blocked_by_scene: trigger:0`), at every knowledge level; once the trigger isn't armed, nothing.
     */
    @Test
    fun `Mahogany's east exit names the scene that turns the player back`() {
        val w = world
        val game = object : dev.kotlinds.pokemonclient.PokemonGame by HgssGame(HgssVersion.HEARTGOLD_US) {
            override val world = w
        }
        val mahogany = assertNotNull(w.areaOf(MAHOGANY))
        val trigger = mahogany.triggers.single { it.zone == MAHOGANY && it.id == 0 }
        val start = (trigger.x - 4) to trigger.y
        fun state(armed: Boolean) = dev.kotlinds.pokemonclient.state.GameState(
            0, dev.kotlinds.pokemonclient.state.Screen.Overworld(null, dev.kotlinds.pokemonclient.state.Awaiting.INPUT), null, emptyList(), null, null,
            dev.kotlinds.pokemonclient.state.FieldState(
                MAHOGANY, game.mapName(MAHOGANY), start.first, start.second, mahogany.tile(start.first, start.second)!!.heights.firstOrNull()?.div(8) ?: 0,
                dev.kotlinds.pokemonclient.Direction.EAST, dev.kotlinds.pokemonclient.state.MovementMode.WALK, false,
                activeTriggers = if (armed) trigger.tiles.toSet() else emptySet(),
                // The seller himself (obj 0) stands on the third tile of the passage, 540,175.
                objects = listOf(dev.kotlinds.pokemonclient.state.FieldObject("person:0", "man", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 540, 175, dev.kotlinds.pokemonclient.Direction.WEST)),
            ),
        )
        val armed = dev.kotlinds.pokemonclient.actions.ReachSurvey(game, state(armed = true), dev.kotlinds.pokemonclient.actions.ActionSettings()).of("exit:east")
        assertEquals("trigger:0", armed.blockedByScene, armed.toString())
        val lifted = dev.kotlinds.pokemonclient.actions.ReachSurvey(game, state(armed = false), dev.kotlinds.pokemonclient.actions.ActionSettings()).of("exit:east")
        assertNull(lifted.blockedByScene)
    }

    private companion object {
        const val MAHOGANY = 87
        const val CIANWOOD = 75
        const val ROUTE_41 = 95
        const val ROUTE_47 = 151
        const val RUINS_OF_ALPH = 113
        const val LIGHTHOUSE_3F = 222
        const val ROCKET_HQ_B2F = 248
    }
}
