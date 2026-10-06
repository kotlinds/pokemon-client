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
import dev.kotlinds.pokemonclient.world.WarpTrigger
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
        assertEquals(WarpTrigger.Press(Direction.NORTH), ladder.trigger)
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

    // region The field moves the story table names (HgssStoryTable descriptions)

    /** Route options with exactly the field moves [kinds] (surfing allowed when Surf is one of them), ledges allowed. */
    private fun moves(vararg kinds: FieldMoveKind) = RouteOptions(canSurf = FieldMoveKind.SURF in kinds, fieldMoves = kinds.toSet(), acceptOneWay = true)

    @Test
    fun `Mt Silver is climbed from Route 28 to Red with Rock Climb and without Rock Smash`() {
        val w = world
        // Route 28 (zone 32) next to the League gate's west door, to the tile in front of Red (summit 465, 30,12).
        fun toRed(options: RouteOptions) = WorldRouter(w).route(32, Node(885, 266), options) { it.zone == 465 && it.node.x == 30 && it.node.y == 12 }
        // Rock Climb alone is enough; the Rock Smash rocks (2F, zone 463: five of them) are off the way.
        assertEquals(5, assertNotNull(w.areaOf(463)).people.count { it.zone == 463 && it.obstacle == FieldMoveKind.ROCK_SMASH })
        assertNotNull(toRed(moves(FieldMoveKind.ROCK_CLIMB)))
        // Without Rock Climb, no way (even with every other field move).
        assertEquals(null, toRed(moves(FieldMoveKind.SURF, FieldMoveKind.WATERFALL, FieldMoveKind.WHIRLPOOL, FieldMoveKind.STRENGTH, FieldMoveKind.ROCK_SMASH, FieldMoveKind.CUT)))
    }

    /**
     * Victory Road (1F 124, 2F 178, 3F 179) needs Strength twice: on 1F the boulder at 43,52 closes the way to the
     * ladder up; from 3F's first part the only way on is the hole at 55,42 down to 2F (57,42), where the boulder at
     * 50,28 closes the way to the ladder at 56,21; past it, 3F leads to holes into 2F's last part and its ladder up to
     * the exit. The router treats boulders as walls ([WorldRouter.staticOverlay]); the pushes are the [PushPlanner]'s.
     */
    @Test
    fun `Victory Road needs Strength on 1F and on 2F after the hole`() {
        val w = world
        val all = moves(FieldMoveKind.SURF, FieldMoveKind.WATERFALL, FieldMoveKind.ROCK_SMASH, FieldMoveKind.ROCK_CLIMB)
        // Boulders in place, no way through; without them the floors are linked.
        assertEquals(null, WorldRouter(w).route(124, Node(46, 58), all) { it.zone == 58 })
        assertNotNull(WorldRouter(w) { _, _ -> dev.kotlinds.pokemonclient.world.Overlay() }.route(124, Node(46, 58), RouteOptions(acceptOneWay = true)) { it.zone == 58 })
        fun floor(zone: Int, from: Node, ladder: Pair<Int, Int>, options: RouteOptions): dev.kotlinds.pokemonclient.world.Route? {
            val area = assertNotNull(w.areaOf(zone))
            return dev.kotlinds.pokemonclient.world.PushPlanner(area, WorldRouter.staticOverlay(area)).route(from, options, setOf(ladder)) { it.x == ladder.first && it.y == ladder.second }
        }
        val strength = moves(FieldMoveKind.STRENGTH)
        // 1F: from the entrance to the ladder up (19,7).
        assertEquals(null, floor(124, Node(46, 58), 19 to 7, RouteOptions(acceptOneWay = true)))
        assertNotNull(floor(124, Node(46, 58), 19 to 7, strength))
        // 2F: from 1F's ladder only the ladder at 51,38 is reached; 3F from there only reaches the hole at 55,42.
        assertNotNull(floor(178, Node(7, 30), 51 to 38, RouteOptions(acceptOneWay = true)))
        val third = assertNotNull(w.areaOf(179))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(third, WorldRouter.staticOverlay(third)).route(Node(49, 39), all, setOf(38 to 13)) { it.x == 38 && it.y == 13 })
        // Below the hole (2F 57,42): the ladder at 56,21 needs the boulder at 50,28 pushed.
        assertEquals(null, floor(178, Node(57, 42), 56 to 21, RouteOptions(acceptOneWay = true)))
        assertNotNull(floor(178, Node(57, 42), 56 to 21, strength))
    }

    @Test
    fun `the Dragons Den shrine is reached only with Whirlpool`() {
        val w = world
        // From the Den's entrance hall (125) down its ladder (6,5) to the shrine (288).
        fun toShrine(options: RouteOptions) = WorldRouter(w).route(125, Node(6, 5), options) { it.zone == 288 }
        assertEquals(null, toShrine(moves(FieldMoveKind.SURF)))
        assertNotNull(toShrine(moves(FieldMoveKind.SURF, FieldMoveKind.WHIRLPOOL)))
    }

    /**
     * HM07 (the item ball at 47,10 on Ice Path 1F, zone 120, zone_event 117_D39R0101.json) lies in the part entered
     * from Route 44 (door 11,58): walked to on 1F alone. From the Blackthorn City door (55,40) the way goes down
     * through B1F, B2F and B3F (237..239) and back up: the whole Ice Path crossed back ([HgssStoryTable] `johto:hm07`).
     */
    @Test
    fun `HM07 lies in the Ice Path part entered from Route 44`() {
        val w = world
        fun toHm07(from: Node) = WorldRouter(w).route(120, from, RouteOptions(acceptOneWay = true)) {
            it.zone == 120 && kotlin.math.abs(it.node.x - 47) + kotlin.math.abs(it.node.y - 10) == 1
        }
        assertEquals(emptyList(), assertNotNull(toHm07(Node(11, 57))).links.map { it.zone })
        assertTrue(237 in assertNotNull(toHm07(Node(55, 39))).links.map { it.zone })
    }

    @Test
    fun `Blaines Gym is reached from Pallet Town by Route 21 and Cinnabar Island`() {
        val w = world
        // Pallet Town (49) by its first house door, surfing, to the Seafoam Islands Gym (457): Route 20, then the cave.
        val route = assertNotNull(WorldRouter(w).route(49, Node(1033, 364), moves(FieldMoveKind.SURF)) { it.zone == 457 })
        assertEquals(listOf(92, 146), route.links.map { it.zone })
    }

    // endregion
}
