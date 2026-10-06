package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.actions.WorldTravel
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.FieldMoveEdge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.WorldRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Navigation across the zones of Kanto's shared outdoor area, on the player's save (bench, DeSmuME; skipped without
 * `POKEMON_ROM`):
 * - `nav_pewter_landed`: Pewter City (map 3) at 1048,107, just landed with Fly. Live, `go_to "Viridian City"` stopped
 *   in front of Route 2's Cut tree (1034,137) with "no way to warp:2": the tree, an object of Route 2, isn't loaded
 *   while the player is in Pewter, the route walked into it, and the refused step forbade Cut from that tile too.
 * - `nav_route20_cinnabar_side`: Route 20 (map 92) at 1056,508, surfing, just come from Cinnabar Island. Live,
 *   `go_to warp:1` (a Seafoam Islands entrance 57 tiles east) set off west, up Route 21.
 */
class HgssCrossMapNavFixtureTest {

    private fun field(name: String): FieldState {
        HgssData.useWorld(HgssWorldRom.require())
        HgssData.useGameData(HgssWorldRom.requireData())
        return assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name)).field)
    }

    private fun live(field: FieldState) = field.objects.map {
        LiveObject(it.x, it.y, it.facing, clearedBy = FieldMoveKind.CUT.takeIf { _ -> it.obstacle == ObstacleKind.CUT_TREE })
    }

    @Test
    fun `from Pewter City the route to Route 2's gatehouse cuts the tree of Route 2`() {
        val field = field("nav_pewter_landed")
        assertEquals(1048 to 107, field.x to field.y)
        val area = assertNotNull(HgssData.world?.areaOf(field.mapId))
        val tree = 1034 to 137
        assertTrue(field.objects.none { it.x to it.y == tree }, "Route 2's objects aren't loaded in Pewter City")
        val neighbours = MovePlans.neighbourObstacles(area, field)
        assertEquals(FieldMoveKind.CUT, neighbours.single { it.x to it.y == tree }.clearedBy)
        // The gatehouse's mat (Route 2 warp:2) from the player: with the obstacles of the other zones, Cut on the tree.
        val gatehouse: (Node) -> Boolean = { it.x == 1050 && it.y == 183 }
        val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.CUT))
        fun route(objects: List<LiveObject>) = assertIs<Pathfinder.Result.Found>(
            Pathfinder(area, Overlay(objects = objects)).route(Node(field.x, field.y), options, setOf(1050 to 183), isGoal = gatehouse),
        ).route
        val cut = route(live(field) + neighbours).edges.filterIsInstance<FieldMoveEdge>().single()
        assertEquals(FieldMoveKind.CUT, cut.move)
        assertEquals(tree, cut.to.x to cut.to.y)
        // Without them (the bug): a plain step into the tree, which the game refuses.
        assertTrue(route(live(field)).edges.any { it is Edge.Step && it.to.x to it.to.y == tree })
    }

    @Test
    fun `on Route 20 the Seafoam entrance on the walled beach is reached only by a loop through Kanto`() {
        val field = field("nav_route20_cinnabar_side")
        assertEquals(92, field.mapId)
        assertEquals(MovementMode.SURF, field.movement)
        val world = HgssWorldRom.require()
        val area = assertNotNull(world.areaOf(92))
        val warp = area.warps.single { it.zone == 92 && it.id == 1 }
        val onWarp: (Node) -> Boolean = { it.x == warp.x && it.y == warp.y }
        val options = RouteOptions(mode = MovementMode.SURF, canSurf = true, fieldMoves = setOf(FieldMoveKind.SURF, FieldMoveKind.CUT, FieldMoveKind.STRENGTH))
        // Rocks all around the beach: no way by surfing from the Cinnabar side.
        val start = Node(field.x, field.y)
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area, Overlay(objects = live(field))).route(start, options, setOf(warp.x to warp.y), isGoal = onWarp))
        // The way the world router finds: more warps than go_to takes for a target of this map (refused before moving).
        val loop = assertNotNull(WorldRouter(world).route(92, start, options, goalTiles = { a -> if (a === area) setOf(warp.x to warp.y) else emptySet() }) { it.area === area && onWarp(it.node) })
        assertTrue(loop.links.size > 8, loop.links.toString())
        // The other entrance, warp:0, is right there.
        val other = area.warps.single { it.zone == 92 && it.id == 0 }
        assertIs<Pathfinder.Result.Found>(Pathfinder(area, Overlay(objects = live(field))).route(start, options, setOf(other.x to other.y)) { it.x == other.x && it.y == other.y })
    }

    /** NOTES: `go_to "Seafoam Gym"` was refused; the map is "Seafoam Islands Cinnabar Gym" (and only it answers). */
    @Test
    fun `Seafoam Gym names Blaine's gym in the Seafoam Islands`() {
        val world = HgssWorldRom.require()
        HgssData.useWorld(world)
        // The maps' own names ([dev.kotlinds.pokemonclient.state.MapName.map]): what loose names are matched against.
        val names = (0 until world.zoneCount).mapNotNull { HgssData.mapName(it).map }
        val matches = names.filter { WorldTravel.wordsMatch(it, "Seafoam Gym") }
        assertEquals(listOf("Seafoam Islands Cinnabar Gym"), matches)
        assertTrue(names.none { WorldTravel.looseMatch(it, "Seafoam Gym") })
    }
}
