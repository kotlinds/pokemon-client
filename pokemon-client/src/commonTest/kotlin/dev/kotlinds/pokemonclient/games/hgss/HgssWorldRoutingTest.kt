package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.ClimbAxis
import dev.kotlinds.pokemonclient.world.FieldMoveEdge
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.TriggerWarp
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The world knowledge `go_to` relies on, on the real HeartGold (USA) ROM (skipped without `POKEMON_ROM`): every
 * tile behavior decoded, bridges, map edges, holes, hidden items and routes across floors. Expected values come
 * from the decomp (zone_event JSON files, scr_seq_D43R0103 / D39R0102 hole scripts) and the agent's notes ("19.
 * Nouveau MCP (v2)": Route 26/27, Victory Road).
 *
 * Zone ids: Route 26 = 30, Route 27 = 31, Indigo Plateau = 58, Victory Road 1F/2F/3F = 124/178/179, Ice Path
 * B1F/B2F = 237/238, Bell Tower 1F = 111, roof = 340.
 */
class HgssWorldRoutingTest {

    private val world get() = HgssWorldRom.require()

    @Test
    fun `every tile behavior of the ROM has a kind`() {
        val w = world
        val unknown = (0 until w.zoneCount).flatMap { zone ->
            val area = w.areaOf(zone) ?: return@flatMap emptyList()
            val bounds = area.zoneBounds[zone] ?: return@flatMap emptyList()
            (bounds[1]..bounds[3]).flatMap { y -> (bounds[0]..bounds[2]).mapNotNull { x -> (area.tile(x, y)?.kind as? TileKind.Unknown)?.behavior?.takeIf { area.zoneAt(x, y) == zone } } }
        }.toSet()
        assertEquals(emptySet(), unknown)
    }

    @Test
    fun `the Route 26 bridges are walked without Surf`() {
        val area = assertNotNull(world.areaOf(30))
        // Notes: wooden bridges 898→912 at y 404, and 914 from y 399 to 388; the start tile on the Route 27 side.
        assertEquals(TileKind.Bridge(overWater = true), area.tile(900, 404)?.kind)
        assertIs<TileKind.Bridge>(area.tile(895, 404)?.kind)
        val east = assertIs<Pathfinder.Result.Found>(Pathfinder(area).route(Node(890, 404), RouteOptions()) { it.x == 916 && it.y == 404 })
        assertTrue(east.route.edges.map { it.to }.any { it.x == 905 && it.y == 404 }, "over the bridge")
        // On the deck (height 48; the water under it is at 8).
        val deck = Pathfinder(area).levelAt(914, 400, 48)
        assertIs<Pathfinder.Result.Found>(Pathfinder(area).route(Node(914, 400, deck), RouteOptions()) { it.x == 914 && it.y == 387 })
    }

    @Test
    fun `Route 27 lists its east edge towards Route 26`() {
        val area = assertNotNull(world.areaOf(31))
        val east = WorldLinks.connections(area, 31).filter { it.direction == Direction.EAST && it.toZone == 30 }
        assertTrue(east.isNotEmpty())
        assertTrue(east.flatMap { it.tiles }.any { it == 895 to 404 }, east.toString())
    }

    @Test
    fun `holes are the trigger warps of Victory Road and Ice Path`() {
        val w = world
        val vr = assertNotNull(w.areaOf(179)).triggerWarps
        assertEquals(
            listOf(TriggerWarp(179, 1, 55, 42, 178, 57, 42), TriggerWarp(179, 2, 26, 38, 178, 28, 38), TriggerWarp(179, 3, 29, 44, 178, 31, 44), TriggerWarp(179, 4, 20, 18, 178, 22, 17), TriggerWarp(179, 5, 56, 28, 178, 58, 28)),
            vr,
        )
        assertEquals(TriggerWarp(237, 0, 11, 10, 238, 12, 12), assertNotNull(w.areaOf(237)).triggerWarps.first())
        // The rival scene trigger and the monk / Ho-Oh scenes are triggers, not holes.
        assertTrue(assertNotNull(w.areaOf(111)).triggerWarps.isEmpty())
        assertTrue(assertNotNull(w.areaOf(340)).triggers.any { it.x == 15 && it.y == 20 })
    }

    @Test
    fun `warps lead to the arrival tile of the other floor`() {
        val w = world
        val links = WorldLinks.links(w, assertNotNull(w.areaOf(178)), 178)
        val ladder = links.single { it.id == "warp:5" }
        assertEquals(Triple(179, 54, 22), Triple(ladder.targetZone, ladder.toX, ladder.toY))
        assertEquals(Direction.NORTH, ladder.exitDirection)
    }

    @Test
    fun `hidden items are not signs`() {
        val signs = assertNotNull(world.areaOf(58)).signs.filter { it.zone == 58 }
        assertEquals(SignKind.SIGN, signs.single { it.id == 0 }.kind)
        // std_hiddenitem_t10_rare_candy (8190) → FLAG_HIDDENITEM 800 + 190.
        val candy = signs.single { it.id == 1 }
        assertEquals(Triple(SignKind.HIDDEN_ITEM, 910 to 209, 990), Triple(candy.kind, candy.x to candy.y, candy.flag))
    }

    @Test
    fun `the router crosses Victory Road floors where the third floor alone has no way`() {
        val w = world
        val third = assertNotNull(w.areaOf(179))
        // From the Indigo Plateau exit (38,14) to the ladder at (54,22): not on 3F alone (notes: "no way… blocked").
        assertIs<Pathfinder.Result.Failed>(Pathfinder(third).route(Node(38, 14), RouteOptions(), setOf(54 to 22)) { it.x == 54 && it.y == 22 })
        val route = assertNotNull(WorldRouter(w) { _, _ -> dev.kotlinds.pokemonclient.world.Overlay() }.route(179, Node(38, 14), RouteOptions(), goalTiles = { if (it === third) setOf(54 to 22) else emptySet() }) {
            it.area === third && it.node.x == 54 && it.node.y == 22
        })
        assertTrue(route.links.any { it.kind == dev.kotlinds.pokemonclient.world.ZoneLink.Kind.HOLE }, route.links.toString())
        assertEquals(178, route.links.last().zone)
    }

    /**
     * Route 20 (zone 92) is cut in two by a line of sea rocks running diagonally (1133..1141, rows 497..511, and the
     * same north of Seafoam at 1102..1105), each rock touching the next only by a corner: the water on both sides
     * looks open on a small map, but no tile of one side is next to a tile of the other. Checked in the game on the
     * bench (surfing east from 1136,500 or north from 1134,498 is refused; the ROM collision equals the RAM on the
     * 4096 loaded tiles). The trainers Lori (1151,500) and Luis (1105,483), and Seafoam's island with Pedro, are on
     * the Fuchsia side.
     */
    @Test
    fun `the rocks across Route 20 close the sea between the Cinnabar and Fuchsia sides`() {
        val area = assertNotNull(world.areaOf(92))
        val surfing = RouteOptions(mode = dev.kotlinds.pokemonclient.state.MovementMode.SURF, canSurf = true, acceptOneWay = true)
        // From the pocket west of the rocks (where the agent was refused), Lori's water isn't reachable on this map...
        assertIs<Pathfinder.Result.Failed>(Pathfinder(area).route(Node(1134, 500), surfing) { it.x == 1140 && it.y == 500 })
        // ...because the rocks only meet by their corners: 1134,498 (west) and 1135,497 (east) are diagonal neighbours.
        assertTrue(area.tile(1135, 498)!!.blocked && area.tile(1134, 497)!!.blocked)
        // From the Fuchsia side it's open water.
        assertIs<Pathfinder.Result.Found>(Pathfinder(area).route(Node(1135, 497), surfing) { it.x == 1150 && it.y == 500 })
    }

    // region Mt. Silver: Rock Climb

    /** The field moves of the 16-badge save (Surf, Waterfall, Whirlpool, Strength, Rock Climb). */
    private val climber = RouteOptions(
        fieldMoves = setOf(FieldMoveKind.SURF, FieldMoveKind.WATERFALL, FieldMoveKind.WHIRLPOOL, FieldMoveKind.STRENGTH, FieldMoveKind.ROCK_CLIMB),
    )

    @Test
    fun `the Upper Mountainside's pockets are linked by Rock Climb walls`() {
        // Zone 459 (D41R0102): two-tile north-south walls between bands of floor 32 units apart (BDHC 144 / 176...).
        val area = assertNotNull(world.areaOf(459))
        assertEquals(TileKind.RockClimb(ClimbAxis.NORTH_SOUTH), area.tile(14, 42)?.kind)
        assertEquals(TileKind.RockClimb(ClimbAxis.NORTH_SOUTH), area.tile(20, 51)?.kind)
        // From the arrival of 2F's warp (18,51) to the ladder up to 3F (14,39): three walls climbed in a row, each
        // landing on the floor of the band above (the chain the agent found by reading the levels by hand).
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(area).route(Node(18, 51), climber, goalTiles = setOf(14 to 39)) { it.x == 14 && it.y == 39 })
        val climbs = found.route.edges.filterIsInstance<FieldMoveEdge>()
        assertEquals(listOf(FieldMoveKind.ROCK_CLIMB), climbs.map { it.move }.distinct())
        assertEquals(listOf(Node(20, 49), Node(16, 45), Node(14, 41)), climbs.map { it.to })
        assertTrue(climbs.all { it.direction == Direction.NORTH })
        // Without Rock Climb, the first wall is named.
        val failure = assertIs<Pathfinder.Result.Failed>(Pathfinder(area).route(Node(18, 51), RouteOptions(), goalTiles = setOf(14 to 39)) { it.x == 14 && it.y == 39 }).failure
        assertEquals(FieldMoveKind.ROCK_CLIMB, assertIs<dev.kotlinds.pokemonclient.world.RouteFailure.NeedsFieldMove>(failure).move)
    }

    @Test
    fun `Mt Silver is crossed from the summit to the Pokemon Center and back with Rock Climb`() {
        val w = world
        // Summit (465) 30,36 (its exit) → Mount Silver Pokecenter 1F (514): 12 warps (the bench walked it, NOTES).
        val down = assertNotNull(WorldRouter(w).route(465, Node(30, 36), climber) { it.zone == 514 })
        assertEquals(12, down.links.size)
        assertEquals(listOf(465, 464, 459, 463, 459, 463, 459, 463, 122, 460, 122, 90), down.links.map { it.zone })
        // And back up to the tile in front of Red (30,12), climbing the walls the other way.
        val up = assertNotNull(WorldRouter(w).route(514, Node(7, 12), climber) { it.zone == 465 && it.node.x == 30 && it.node.y == 12 })
        assertEquals(465, up.end.zone)
        val climbed = up.places.zipWithNext().filter { (a, b) -> a.area === b.area && kotlin.math.abs(a.node.x - b.node.x) + kotlin.math.abs(a.node.y - b.node.y) > 1 }
        // The 1F wall (44,59 → 44,53), the Upper Mountainside's three, and 3F's ten-tile wall (13,14 → 13,3).
        assertEquals(
            listOf(122 to Node(44, 53), 459 to Node(20, 49), 459 to Node(16, 45), 459 to Node(14, 41), 464 to Node(13, 3)),
            climbed.map { (_, b) -> b.zone to b.node },
        )
        // Without Rock Climb, no way at all (every way up crosses a wall).
        assertEquals(null, WorldRouter(w).route(465, Node(30, 36), climber.copy(fieldMoves = climber.fieldMoves - FieldMoveKind.ROCK_CLIMB)) { it.zone == 514 })
    }

    // endregion
}
