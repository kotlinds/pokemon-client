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
import dev.kotlinds.pokemonclient.world.Elevator
import dev.kotlinds.pokemonclient.world.ElevatorOperator
import dev.kotlinds.pokemonclient.world.ElevatorStop
import dev.kotlinds.pokemonclient.world.StepWeights
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.ZoneLink
import dev.kotlinds.pokemonclient.state.EventFlags
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.actions.MovePlans
import dev.kotlinds.pokemonclient.actions.MoveOptions
import dev.kotlinds.pokemonclient.actions.WorldTravel
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.RouteWarning
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

    // region Trips planned again on every arrival (WorldTravel: the walk follows the first link, then plans again)

    /** The save's event flags with every flag clear (the story's start: everyone hidden by a flag is there). */
    private val noFlagSet = EventFlags(ByteArray(FLAG_BYTES))

    /** The save's event flags with every flag set (everyone hidden by a flag is gone, every trainer beaten). */
    private val everyFlagSet = EventFlags(ByteArray(FLAG_BYTES) { -1 })

    /**
     * Plays a trip the way `go_to` does: the route from where the player stands, its first link taken, the player
     * where the router says they arrive, planned again from there... until the goal's map. Returns the links taken.
     * Fails when a link would be taken twice (the trip going back and forth) or when it never gets there.
     */
    private fun trip(zone: Int, start: Node, options: RouteOptions, flags: EventFlags, isGoal: (WorldRouter.Place) -> Boolean): List<ZoneLink> {
        val router = WorldRouter(world) { _, area -> WorldRouter.knownOverlay(area, flags) }
        val taken = mutableListOf<ZoneLink>()
        var here = zone to start
        repeat(MAX_TRIP) {
            val route = assertNotNull(router.route(here.first, here.second, options, isGoal = isGoal), "no route from ${here.second} on ${here.first} after $taken")
            val link = route.links.firstOrNull() ?: return taken
            assertTrue(taken.none { it.zone == link.zone && it.x == link.x && it.y == link.y }, "${link.id} of ${link.zone} taken again after $taken")
            taken += link
            // Where the router puts the player once through: the place after the link's tile (a ladder within one
            // map, Diglett's Cave's, leads to the same zone: its first place there would be the start).
            val arrival = route.places.zipWithNext().firstOrNull { (a, b) -> a.zone == link.zone && a.node.x == link.x && a.node.y == link.y && b.zone == link.targetZone }?.second
                ?: route.places.first { it.zone == link.targetZone }
            here = link.targetZone to arrival.node
        }
        error("not there after $MAX_TRIP links: $taken")
    }

    /** The warp tiles of the maps [route] crosses that it doesn't take: none of them is ever stepped on. */
    private fun steppedOnUnplannedWarps(route: WorldRouter.WorldRoute): List<WorldRouter.Place> {
        val planned = route.links.map { Triple(it.zone, it.x, it.y) }.toSet()
        return route.places.filter { p ->
            val zone = p.zone ?: return@filter false
            p.area.warps.any { it.zone == zone && it.x == p.node.x && it.y == p.node.y && it.trigger == WarpTrigger.Enter } &&
                Triple(zone, p.node.x, p.node.y) !in planned
        }
    }

    /**
     * Bell Tower 3F (333) / 4F (334): the ladder climbed north at 24,8 on 3F comes out of the hole at 24,8 on 4F, and
     * the game leaves the player north of it (24,7); down the hole, the player stands south of 3F's ladder (24,9).
     * Measured on the bench (NOTES race: "4F 24,7 → 3F 24,9"). Planned from the hole's tile instead, going back down
     * and up again looked like a short cut to the corridor south of the hole: the race's 13-warp ping-pong.
     */
    @Test
    fun `the Bell Tower ladders leave the player off the ladder`() {
        val w = world
        val third = WorldLinks.links(w, assertNotNull(w.areaOf(333)), 333).single { it.id == "warp:1" }
        assertEquals(Triple(334, 24 to 8, Direction.NORTH), Triple(third.targetZone, third.toX!! to third.toY!!, third.arrivalStep))
        val fourth = WorldLinks.links(w, assertNotNull(w.areaOf(334)), 334).single { it.id == "warp:0" }
        assertEquals(Triple(333, 24 to 8, Direction.SOUTH), Triple(fourth.targetZone, fourth.toX!! to fourth.toY!!, fourth.arrivalStep))
        // A door or a mat leaves the player on its tile (no step).
        assertEquals(null, WorldLinks.links(w, assertNotNull(w.areaOf(111)), 111).single { it.id == "warp:0" }.arrivalStep)
        // The router lands on 24,7 and goes on from there.
        val route = assertNotNull(WorldRouter(w).route(333, Node(24, 9), RouteOptions(acceptOneWay = true)) { it.zone == 335 })
        assertEquals(Node(24, 7), route.places.first { it.zone == 334 }.node)
    }

    /**
     * Nathan's rule: a warp planned in the route is taken, a warp not planned is never stepped on (it would take the
     * player elsewhere). Bell Tower 3F → 5F takes 3F's ladder and 4F's ladder up; on 4F the hole down (24,8), right
     * next to the arrival, is never stepped on: neither from 24,7, nor standing on it (just arrived on the ladder).
     */
    @Test
    fun `the Bell Tower route takes its ladders and never steps on the hole it doesn't plan`() {
        val w = world
        val options = RouteOptions(acceptOneWay = true)
        val up = assertNotNull(WorldRouter(w) { _, a -> WorldRouter.knownOverlay(a, noFlagSet) }.route(333, Node(24, 9), options) { it.zone == 335 })
        assertEquals(listOf("warp:1" to 333, "warp:6" to 334), up.links.map { it.id to it.zone })
        assertEquals(emptyList(), steppedOnUnplannedWarps(up))
        for (start in listOf(Node(24, 7), Node(24, 8))) {
            val route = assertNotNull(WorldRouter(w).route(334, start, options) { it.zone == 335 }, "from $start")
            assertEquals(listOf("warp:6"), route.links.map { it.id }, "from $start")
            assertEquals(emptyList(), steppedOnUnplannedWarps(route), "from $start")
            assertTrue(route.places.none { it.zone == 334 && it.node.x == 24 && it.node.y == 8 }, "from $start: ${route.places}")
        }
    }

    /**
     * The race's three ping-pongs (13 warps each), played again link by link: planned again from where each link
     * leaves the player, the trip never takes a warp twice.
     * - Bell Tower 4F (24,7) → 5F, and from 3F (24,9);
     * - Burned Tower B1F (19,16) → the Ecruteak Gym (80): up the ladder (B1F 23,16 → 1F 22,16, left at 22,15), out;
     * - Ilex Forest (11,74) → Route 34 (38): north through the forest with Cut, or round by Azalea's gatehouse
     *   without it, never back into the forest from the gatehouse.
     */
    @Test
    fun `planned again on every arrival a trip never takes a warp twice`() {
        // The soft costs go_to plans with, running (the tower's wild Pokémon on every floor tile): with them, two
        // warps (no encounter) looked cheaper than five floor tiles when the arrival was thought to be the hole's tile.
        val ladders = RouteOptions(acceptOneWay = true, weights = StepWeights.of(world, EncounterConditions(landMovement = MovementMode.RUN)))
        assertEquals(listOf("warp:6"), trip(334, Node(24, 7), ladders, noFlagSet) { it.zone == 335 }.map { it.id })
        assertEquals(listOf("warp:1", "warp:6"), trip(333, Node(24, 9), ladders, noFlagSet) { it.zone == 335 }.map { it.id })
        val burned = trip(217, Node(19, 16), moves(FieldMoveKind.SURF, FieldMoveKind.STRENGTH, FieldMoveKind.CUT), everyFlagSet) { it.zone == 80 }
        assertEquals(listOf(217, 7, 78), burned.map { it.zone })
        for (flags in listOf(noFlagSet, everyFlagSet)) {
            for (options in listOf(moves(FieldMoveKind.CUT), moves())) {
                val ilex = runCatching { trip(117, Node(11, 74), options, flags) { it.zone == 38 } }
                // No way at all is fine (the forest's people in the way); a way is never a ping-pong.
                ilex.exceptionOrNull()?.let { e -> assertTrue("no route" in e.message.orEmpty(), "Ilex ${options.fieldMoves}: ${e.message}") }
                ilex.getOrNull()?.let { links -> assertTrue(links.count { it.zone == 117 } <= 1, "Ilex ${options.fieldMoves}: $links") }
            }
        }
    }

    /**
     * Radio Tower 5F (189) from 3,5 to its lift door (warp:1, 13,2): the way goes down to 3F and across it, where a
     * door (objects 7 and 8 at 23,11 / 24,11, hidden by flag 447 once opened) closes the corridor. The map data alone
     * (boulders and trees only) planned through it: the race's three trips down to 3F to find it closed. With the
     * people the save places there ([WorldRouter.knownOverlay]) there is no way before the first step; once the door's
     * flag is set, the way is back.
     */
    @Test
    fun `a closed door of another floor closes the plan before the first step`() {
        val w = world
        val five = assertNotNull(w.areaOf(189))
        val lift = five.warps.single { it.zone == 189 && it.id == 1 }
        fun route(overlay: (dev.kotlinds.pokemonclient.world.Area) -> dev.kotlinds.pokemonclient.world.Overlay) =
            WorldRouter(w) { _, a -> overlay(a) }.route(189, Node(3, 5), RouteOptions(acceptOneWay = true), goalTiles = { if (it === five) setOf(lift.x to lift.y) else emptySet() }) {
                it.area === five && it.node.x == lift.x && it.node.y == lift.y
            }
        val blind = assertNotNull(route(WorldRouter::staticOverlay))
        assertTrue(blind.places.any { it.zone == 187 && it.node.x == 23 && it.node.y == 11 }, blind.places.toString())
        assertEquals(null, route { WorldRouter.knownOverlay(it, noFlagSet) })
        val open = EventFlags(ByteArray(FLAG_BYTES).also { it[DOOR_FLAG / 8] = (1 shl (DOOR_FLAG % 8)).toByte() })
        assertNotNull(route { WorldRouter.knownOverlay(it, open) })
    }

    /**
     * The Cinnabar Gym (457, in the Seafoam Islands): its entry script (scr_seq_D11R0106_008, `MovePersonFacing`)
     * puts its three beaten trainers (objects 2, 5, 7) out of the corridors; where the map places them they close the
     * way to Blaine. Review impl13 H1: planned from outside with the people the save places there, go_to answered "no
     * way" before the first step. The people a script moves aren't walls of another map; the others still are (the
     * Radio Tower's door above).
     */
    @Test
    fun `people an entry script moves are not walls of another map`() {
        val w = world
        val gym = assertNotNull(w.areaOf(CINNABAR_GYM))
        assertEquals(setOf(2, 5, 7), gym.people.filter { it.zone == CINNABAR_GYM && it.scriptMoved }.map { it.id }.toSet())
        val door = w.areaOf(SEAFOAM_1F)!!.warps.first { it.zone == SEAFOAM_1F && it.targetZone == CINNABAR_GYM }
        val blaine = gym.people.single { it.zone == CINNABAR_GYM && it.id == 0 }
        val outside = assertNotNull(w.areaOf(SEAFOAM_1F))
        val start = Direction.entries.map { Node(door.x + it.dx, door.y + it.dy) }.first { n -> outside.tile(n.x, n.y)?.blocked == false }
        fun route(overlay: (dev.kotlinds.pokemonclient.world.Area) -> dev.kotlinds.pokemonclient.world.Overlay) =
            WorldRouter(w) { _, a -> overlay(a) }.route(SEAFOAM_1F, start, RouteOptions(acceptOneWay = true)) {
                it.area === gym && it.node.x == blaine.x && it.node.y == blaine.y + 1
            }
        assertNotNull(route { WorldRouter.knownOverlay(it, noFlagSet) })
        // Where the map places them, they would close it (what the plan assumed before).
        val templates = { a: dev.kotlinds.pokemonclient.world.Area ->
            WorldRouter.staticOverlay(a).let { o ->
                o.copy(objects = o.objects + a.people.filter { it.obstacle == null && !it.wanders && it.presentWith(noFlagSet) }.map { LiveObject(it.x, it.y, it.facing) })
            }
        }
        assertEquals(null, route(templates))
    }

    /**
     * Route 27 (31) to Route 26 (30) surfing east with Waterfall (Tohjo Falls, 126, in between): through the falls,
     * even avoiding the unbeaten trainers of Route 27 (NOTES race: refused as 19 warps through Kanto with
     * avoid_trainers; the way is two warps).
     */
    @Test
    fun `Route 27 to Route 26 goes through Tohjo Falls`() {
        val w = world
        for (avoid in listOf(false, true)) {
            val options = moves(FieldMoveKind.SURF, FieldMoveKind.WATERFALL).copy(avoidTrainers = avoid, acceptOneWay = false)
            val route = assertNotNull(WorldRouter(w) { _, a -> WorldRouter.knownOverlay(a, noFlagSet) }.route(31, Node(704, 398), options) { it.zone == 30 }, "avoid=$avoid")
            assertEquals(listOf(31 to 126, 126 to 31), route.links.map { it.zone to it.targetZone }, "avoid=$avoid")
        }
        // go_to's rule ([WorldTravel.chooseWay]): the way avoiding the trainers is no detour next to the shortest (the
        // same falls), so it is taken as asked, nothing refused nor replaced.
        val plain = moves(FieldMoveKind.SURF, FieldMoveKind.WATERFALL).copy(acceptOneWay = false)
        fun route(options: RouteOptions) = assertNotNull(WorldRouter(w) { _, a -> WorldRouter.knownOverlay(a, noFlagSet) }.route(31, Node(704, 398), options) { it.zone == 30 })
        val avoiding = route(plain.copy(avoidTrainers = true))
        val choice = WorldTravel.chooseWay(avoiding, local = false, MoveOptions(avoidTrainers = true)) { route(plain) }
        assertEquals(WorldTravel.WayChoice.Go(avoiding), choice)
        // Without Waterfall the falls' cave isn't crossed (its way up is a waterfall).
        val surfOnly = WorldRouter(w).route(31, Node(704, 398), moves(FieldMoveKind.SURF).copy(acceptOneWay = false)) { it.zone == 30 }
        assertTrue(surfOnly == null || surfOnly.links.none { it.targetZone == 126 }, surfOnly?.links.toString())
    }

    /**
     * A long trip is legitimate (Nathan's decision: no refusal for the number of warps): from Cerulean City's Pokémon
     * Center to Ecruteak City on foot (the field moves of that point of the game), story advanced (every flag set: Sudowoodo gone, the Snorlax woken), the way takes
     * more than 12 warps (16: Underground Path, Diglett's Cave, Route 2's gatehouse, the League's reception gate, Route
     * 31's and 36's gatehouses...), once refused as "too far for one go_to". It is the shortest way, so go_to's rule
     * ([WorldTravel.chooseWay]) takes it as planned, and a trip planned again on every arrival gets there without taking
     * any link twice. (Ecruteak City to New Bark Town, the example of the decision, is 5 warps: never concerned.)
     */
    @Test
    fun `a trip of more than twelve warps across the region is taken`() {
        val w = world
        val center = assertNotNull(w.areaOf(CERULEAN_CENTER))
        val door = center.warps.first { it.zone == CERULEAN_CENTER }
        val start = Node(door.x, door.y - 1)
        val options = moves(FieldMoveKind.SURF, FieldMoveKind.CUT, FieldMoveKind.STRENGTH, FieldMoveKind.ROCK_SMASH, FieldMoveKind.WATERFALL, FieldMoveKind.WHIRLPOOL)
        val route = assertNotNull(WorldRouter(w) { _, a -> WorldRouter.knownOverlay(a, everyFlagSet) }.route(CERULEAN_CENTER, start, options) { it.zone == ECRUTEAK })
        assertTrue(route.links.size > 12, route.links.toString())
        assertEquals(WorldTravel.WayChoice.Go(route), WorldTravel.chooseWay(route, local = false, MoveOptions()) { route })
        val taken = trip(CERULEAN_CENTER, start, options, everyFlagSet) { it.zone == ECRUTEAK }
        assertTrue(taken.size > 12, taken.toString())
    }

    /**
     * Route 38 (42): Sailor Harry (object 4, 336,166) turns between west and east on his own (movement type 5), Lass
     * Dana (2) looks south, west and east (13), Bird Keeper Toby (1) only east (17). A route planned while Harry faced
     * north walked up to his east side and was seen when he turned (NOTES race, Codex at 338,166 with avoid_trainers):
     * his sight counts both ways, whichever way he faces now.
     */
    @Test
    fun `a trainer turning on its own watches every way it turns to`() {
        val area = assertNotNull(world.areaOf(42))
        fun template(id: Int) = area.people.single { it.zone == 42 && it.id == id }
        assertEquals(setOf(Direction.WEST, Direction.EAST), template(4).looks)
        assertEquals(setOf(Direction.SOUTH, Direction.WEST, Direction.EAST), template(2).looks)
        assertEquals(emptySet(), template(1).looks)
        val harry = WorldRouter.knownPeople(area, noFlagSet) { it == 42 }.single { it.x == 336 && it.y == 166 }.copy(facing = Direction.NORTH)
        fun warnings(trainer: LiveObject) = assertIs<Pathfinder.Result.Found>(
            Pathfinder(area, Overlay(objects = listOf(trainer))).route(Node(341, 166), RouteOptions()) { it.x == 338 && it.y == 166 },
        ).route.warnings
        assertTrue(RouteWarning.PassesTrainerSight in warnings(harry))
        // Facing north and nothing else, the tile two east of him would look safe (what the race's route assumed).
        assertTrue(RouteWarning.PassesTrainerSight !in warnings(harry.copy(looks = emptySet())))
    }

    /**
     * The trainers of the next map of the overworld, unknown live until the player crosses the border, are where
     * their map places them with their sight ([MovePlans.neighbourObjects]): from Route 38's west end, Route 39's
     * Sailor Eugene (273,200, watching 4 tiles) is avoided like a trainer of Route 38; once beaten (his flag set), he
     * watches nothing. Route 39's Pokéfans walk back and forth: where the map places them isn't where they are.
     */
    @Test
    fun `the trainers of the next map are known before crossing the border`() {
        val area = assertNotNull(world.areaOf(42))
        val field = FieldState(42, MapName(42), 289, 170, 0, Direction.WEST, MovementMode.WALK, moving = false)
        fun eugene(flags: EventFlags) = MovePlans.neighbourObjects(area, field, flags).single { it.x == 273 && it.y == 200 }
        assertEquals(4, eugene(noFlagSet).sightRange)
        assertEquals(0, eugene(everyFlagSet).sightRange)
        // Derek (278,203) walks back and forth: left out.
        assertTrue(MovePlans.neighbourObjects(area, field, noFlagSet).none { it.x == 278 && it.y == 203 })
        // Route 38's own objects are the live ones (none here: the state has no object), never doubled from the map.
        assertTrue(MovePlans.neighbourObjects(area, field, noFlagSet).none { it.x == 336 && it.y == 166 })
    }

    /**
     * Victory Road from its entrance (1F 46,59, from the League's reception gate) to the Indigo Plateau, in the game's
     * direction: the only things in the way are Strength boulders (go_to walks to the next link and pushes them there,
     * [dev.kotlinds.pokemonclient.actions.WorldTravel]: played end to end on the bench, 1F, 2F, 3F, the hole back to
     * 2F, 3F again, out through 38,13). 3F's warp at 38,14 is only where the plateau's door lands (nothing takes it:
     * the race's "warp:0 at 38,14 didn't take the player anywhere"); the way out is the one at 38,13. And 10,25 on 2F
     * (the race's `go_to map:"Victory Road 2F" 10,25`, "not connected") is a wall, next to the boulder at 9,25.
     */
    @Test
    fun `Victory Road from the entrance to the plateau is closed only by Strength boulders`() {
        val w = world
        val options = moves(FieldMoveKind.STRENGTH, FieldMoveKind.SURF, FieldMoveKind.ROCK_SMASH)
        val first = assertNotNull(w.areaOf(124))
        val relaxed = assertNotNull(WorldRouter(w).route(124, Node(46, 59, Pathfinder(first).levelAt(46, 59, 8 * 16)), options, relaxed = true, ignorePeople = true) { it.zone == 58 })
        val blockers = relaxed.places.mapNotNull { p -> Pathfinder(p.area, WorldRouter.staticOverlay(p.area)).blockerAt(p.node.x, p.node.y, options) }
        assertTrue(blockers.isNotEmpty())
        assertTrue(blockers.all { (it as? dev.kotlinds.pokemonclient.world.RouteFailure.NeedsFieldMove)?.move == FieldMoveKind.STRENGTH }, blockers.toString())
        assertEquals(Triple(179, 38, 13), relaxed.links.last().let { Triple(it.zone, it.x, it.y) })
        val third = WorldLinks.links(w, assertNotNull(w.areaOf(179)), 179)
        assertEquals(WarpTrigger.Never, third.single { it.x == 38 && it.y == 14 }.trigger)
        assertEquals(WarpTrigger.Enter, third.single { it.x == 38 && it.y == 13 }.trigger)
        assertEquals(TileKind.Wall, assertNotNull(w.areaOf(178)).tile(10, 25)?.kind)
    }

    /**
     * The lifts, read from their rooms' scripts (`SetDynamicWarp`): the Olivine Lighthouse's (446) and the Radio
     * Tower's (447) go by themselves between their two floors (scr_seq_D27R0108_000, scr_seq_D23R0107_000), the
     * Goldenrod Dept. Store's (441) is sent by its attendant (person:0, scr_seq_T25R1007_000) to one of seven floors,
     * the Celadon Condominiums' left lift (443) asks for one of three floors on entering. Silph Co.'s (445) runs a
     * common script: unknown. A room without a dynamic way out isn't a lift.
     */
    @Test
    fun `the lifts are read from their rooms' scripts`() {
        val w = world
        assertEquals(Elevator(446, listOf(0), listOf(ElevatorStop(225, 1), ElevatorStop(115, 1)), ElevatorOperator.Shuttle), w.elevatorOf(446))
        assertEquals(Elevator(447, listOf(0), listOf(ElevatorStop(190, 0), ElevatorStop(189, 1)), ElevatorOperator.Shuttle), w.elevatorOf(447))
        val store = assertNotNull(w.elevatorOf(441))
        assertEquals(ElevatorOperator.Attendant(0), store.operator)
        assertEquals(listOf(191, 192, 193, 194, 195, 196, 200), store.stops.map { it.zone })
        assertEquals(ElevatorOperator.EntryMenu, w.elevatorOf(443)?.operator)
        assertEquals(null, w.elevatorOf(445))
        assertEquals(null, w.elevatorOf(111))
    }

    /**
     * From the Olivine Lighthouse's light room (225) to Olivine City (wherever the player stands in the tower): down
     * the lift (NOTES race: "no way to Cianwood City … nor through its warps", then "no way to map:77 from 3,4
     * (Olivine Lighthouse Elevator)"); the Goldenrod Dept. Store's lift, whose floor is chosen, is no way by itself.
     */
    @Test
    fun `a lift going by itself is a way across floors`() {
        val w = world
        val top = assertNotNull(w.areaOf(225))
        val route = assertNotNull(WorldRouter(w).route(225, Node(8, 6, Pathfinder(top).levelAt(8, 6, 0)), RouteOptions(acceptOneWay = true)) { it.zone == OLIVINE })
        assertTrue(route.links.any { it.zone == 446 && it.targetZone == 115 }, route.links.toString())
        val inside = assertNotNull(w.areaOf(446))
        assertNotNull(WorldRouter(w).route(446, Node(3, 4, Pathfinder(inside).levelAt(3, 4, 0)), RouteOptions()) { it.zone == 115 })
        val store = assertNotNull(w.areaOf(441))
        assertEquals(null, WorldRouter(w).route(441, Node(3, 4, Pathfinder(store).levelAt(3, 4, 0)), RouteOptions()) { it.zone == 195 })
    }

    private companion object {
        /** Enough bytes for every event flag of the save. */
        const val FLAG_BYTES = 0x1000

        /** Olivine City's outdoor map. */
        const val OLIVINE = 77

        /** Radio Tower 3F's door (objects 7 and 8): hidden once this flag is set. */
        /** `MAP_SEAFOAM_ISLANDS_CINNABAR_GYM` and `MAP_SEAFOAM_ISLANDS_1F`'s zone (the gym's way in). */
        const val CINNABAR_GYM = 457
        const val SEAFOAM_1F = 146

        const val DOOR_FLAG = 447

        /** A trip of the tests takes at most this many links (the longest: Cerulean City to Ecruteak City, 16). */
        const val MAX_TRIP = 30

        /** Cerulean City's Pokémon Center (1F) and Ecruteak City's outdoor map. */
        const val CERULEAN_CENTER = 428
        const val ECRUTEAK = 78
    }

    // endregion
}