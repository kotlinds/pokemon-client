package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.MovementMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The soft step weights ([StepWeights]): the expected time a step loses to a wild encounter or a trainer, always
 * counted by routes (unlike the avoid options, which ban). Areas in ASCII, every tile in zone [ZONE]: '.' floor,
 * '#' wall, '"' tall grass, 'c' cave floor, '~' surfable water with wild Pokémon, '=' calm surfable water (none),
 * 'v' / '>' ledges jumped south / east.
 */
class StepWeightsTest {

    private val zone = 7

    private fun area(vararg rows: String): Area {
        val width = rows.maxOf { it.length }
        val tiles = Array<TileInfo?>(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { ' ' }) {
                '.' -> TileInfo(false, TileKind.Floor, listOf(0))
                '"' -> TileInfo(false, TileKind.TallGrass, listOf(0))
                '~' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true))
                '=' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true, wildEncounters = false))
                '#' -> TileInfo(true, TileKind.Wall)
                'c' -> TileInfo(false, TileKind.Cave, listOf(0))
                'v' -> TileInfo(false, TileKind.Ledge(Direction.SOUTH))
                '>' -> TileInfo(false, TileKind.Ledge(Direction.EAST))
                else -> null
            }
        }
        return Area(0, "test", 0, 0, width, rows.size, tiles, zones = IntArray(width * rows.size) { zone })
    }

    /**
     * A world whose zone [zone] rolls [land] on land walking, [landRunning] running or cycling (the same by default:
     * a roll that doesn't depend on the pace, like Platinum's), and [surfing] on the water.
     */
    private fun world(land: Double, surfing: Double, landRunning: Double = land) = object : WorldSource {
        override fun areaOf(zoneId: Int): Area? = null
        override val zoneCount = 10
        override val encounterTables = EncounterTables.DECODED
        override fun encounterChance(zoneId: Int, water: Boolean, conditions: EncounterConditions) = when {
            zoneId != zone -> 0.0
            water -> surfing
            conditions.landMovement == MovementMode.WALK -> land
            else -> landRunning
        }
    }

    private fun Pathfinder.to(x: Int, y: Int, from: Node, options: RouteOptions) = route(from, options) { it.x == x && it.y == y }

    private fun Route.tiles() = edges.flatMap { it.tiles }

    @Test
    fun anEncounterCheckCostsItsChanceTimesAFledBattleInStepsOfTheMovement() {
        // Route 1 (rate 20) running: 40% × 20% = 8% a check, 8% of 1,000 frames = 80 frames, 10 running steps.
        assertEquals(10, StepWeights.steps(0.08, MovementMode.RUN))
        // Walking rolls half as often but a step lasts twice as long (16 frames): 40 frames, 2.5 → 3 steps.
        assertEquals(3, StepWeights.steps(0.04, MovementMode.WALK))
        // A trainer battle: 4,000 frames, 500 running steps, 250 walking ones.
        assertEquals(500, StepWeights.of(null, EncounterConditions(MovementMode.RUN)).trainerSight)
        assertEquals(250, StepWeights.of(null, EncounterConditions(MovementMode.WALK)).trainerSight)
        val weights = StepWeights.of(world(land = 0.08, surfing = 0.06), EncounterConditions(MovementMode.RUN))
        assertEquals(mapOf(zone to 10), weights.landEncounter)
        // Surfing: 6% of 1,000 frames in surfing steps (8 frames).
        assertEquals(mapOf(zone to 8), weights.surfEncounter)
        // Nothing in a zone without encounters, nor on calm water, nor outside any zone.
        assertEquals(0, weights.encounter(TileInfo(false, TileKind.Floor), zone))
        assertEquals(0, weights.encounter(TileInfo(false, TileKind.Water(surfable = true, fishable = true, wildEncounters = false)), zone))
        assertEquals(0, weights.encounter(TileInfo(false, TileKind.TallGrass), null))
        assertEquals(10, weights.encounter(TileInfo(false, TileKind.Cave), zone))
    }

    @Test
    fun grassWalkedWhileTheTripRunsCostsTheWalkingRollAndTheTimeWalkingLoses() {
        // Running elsewhere, walking onto the grass (the default of go_to): the walking roll (here 4 %), in running
        // steps (8 frames): 40 frames = 5 steps, plus one step for the slower move (16 frames instead of 8).
        val weights = StepWeights.of(world(land = 0.04, surfing = 0.0, landRunning = 0.08), EncounterConditions(MovementMode.WALK, travelMovement = MovementMode.RUN))
        assertEquals(mapOf(zone to 5), weights.landEncounter)
        assertEquals(setOf(zone), weights.walkedZones)
        assertEquals(1, weights.walkedTileCost)
        assertEquals(500, weights.trainerSight)
        val grass = TileInfo(false, TileKind.TallGrass)
        assertTrue(weights.walksOnto(grass, zone))
        assertEquals(6, weights.moveOnto(grass, zone))
        // A turn in place is an encounter check, not a walked tile.
        assertEquals(5, weights.encounter(grass, zone))
        assertEquals(0, weights.moveOnto(TileInfo(false, TileKind.Floor), zone))
        // Nothing can appear (no table, or a Repel strong enough): the grass is run through.
        val none = StepWeights.of(world(land = 0.0, surfing = 0.0), EncounterConditions(MovementMode.WALK, travelMovement = MovementMode.RUN))
        assertTrue(!none.walksOnto(grass, zone))
        // A roll that doesn't depend on the pace (Platinum's): walking gains nothing, the grass is run through.
        val flat = StepWeights.of(world(land = 0.04, surfing = 0.0), EncounterConditions(MovementMode.WALK, travelMovement = MovementMode.RUN))
        assertTrue(flat.walkedZones.isEmpty() && !flat.walksOnto(grass, zone))
        assertEquals(mapOf(zone to 5), flat.landEncounter)
        // Running everywhere: nothing is walked.
        assertTrue(StepWeights.of(world(land = 0.08, surfing = 0.0), EncounterConditions(MovementMode.RUN)).walkedZones.isEmpty())
    }

    @Test
    fun aGrassFreeWayAFewTilesLongerWinsOverCrossingTheGrass() {
        // Straight through 3 grass tiles (4 moves), or around them by the top row (6 moves, a turn).
        val map = area(
            ".....",
            "\"\"\"\".",
            "#####",
        )
        val weights = StepWeights(landEncounter = mapOf(zone to 10))
        val plain = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(4, 1, Node(0, 1), RouteOptions()))
        assertEquals(4, plain.route.edges.size, "without weights: straight through the grass")
        val weighted = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(4, 1, Node(0, 1), RouteOptions(weights = weights)))
        // The start tile is grass already (no check: the player is on it), the others are avoided.
        assertTrue(weighted.route.tiles().none { map.tile(it.x, it.y)?.kind == TileKind.TallGrass }, weighted.route.toString())
    }

    @Test
    fun aDetourLongerThanTheExpectedLossIsNotTaken() {
        // One grass tile (weight 3, a walking Route 1) against a 40-tile detour: crossing is faster on average.
        val row = ".".repeat(21)
        val map = area(
            row,
            "\"" + "#".repeat(19) + ".",
            row,
        )
        val weights = StepWeights(landEncounter = mapOf(zone to 3))
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0), RouteOptions(weights = weights)))
        assertEquals(2, found.route.edges.size)
        assertEquals(RouteWarning.CrossesTallGrass(1), found.route.warnings.filterIsInstance<RouteWarning.CrossesTallGrass>().single())
    }

    @Test
    fun theAvoidOptionsStayBans() {
        // The same map: with avoid_tall_grass, the 40-tile detour whatever the soft weight.
        val row = ".".repeat(21)
        val map = area(
            row,
            "\"" + "#".repeat(19) + ".",
            row,
        )
        val options = RouteOptions(avoidTallGrass = true, weights = StepWeights(landEncounter = mapOf(zone to 3)))
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(0, 2, Node(0, 0), options))
        assertTrue(found.route.warnings.none { it is RouteWarning.CrossesTallGrass })
        assertTrue(found.route.edges.size > 40)
    }

    @Test
    fun aTurnInTheGrassCostsAnEncounterCheck() {
        val map = area(
            "\"\"\"",
            "...",
        )
        val weights = StepWeights(landEncounter = mapOf(zone to 10))
        val pathfinder = Pathfinder(map)
        assertEquals(RouteOptions.TURN_COST + 10, pathfinder.turnCostAt(Node(0, 0), RouteOptions(weights = weights)))
        assertEquals(RouteOptions.TURN_COST, pathfinder.turnCostAt(Node(0, 1), RouteOptions(weights = weights)))
        // Without soft costs (looking for what blocks a route), a turn is a turn.
        assertEquals(RouteOptions.TURN_COST, pathfinder.turnCostAt(Node(0, 0), RouteOptions(weights = weights), soft = false))
    }

    /** A long pond between two shores, walled off at the bottom: around it is 25 moves, across 5 (and Surf). */
    private fun pond(water: Char) = area(*(listOf("......") + List(19) { ".$water$water$water$water." } + listOf("######")).toTypedArray())

    @Test
    fun aLandWayAFewTilesLongerWinsOverSurfing() {
        val map = pond('~')
        val surf = RouteOptions(canSurf = true, fieldMoves = setOf(FieldMoveKind.SURF), turnCost = 0)
        // Without weights: Surf from the shore (the prompt costs 13 steps) and 4 tiles across, 18 against 25 around.
        val plain = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(5, 10, Node(0, 10), surf))
        assertEquals(5, plain.route.edges.size)
        // Four encounter checks on the water at 8 steps each (a 15 rate surfing): around the pond is faster on average.
        val weights = StepWeights(surfEncounter = mapOf(zone to 8))
        val weighted = assertIs<Pathfinder.Result.Found>(Pathfinder(map).to(5, 10, Node(0, 10), surf.copy(weights = weights)))
        assertTrue(weighted.route.tiles().none { map.tile(it.x, it.y)?.kind is TileKind.Water }, weighted.route.toString())
        assertEquals(25, weighted.route.edges.size)
        // On calm water (no wild Pokémon) the soft weight changes nothing.
        val calm = pond('=')
        val calmWeighted = assertIs<Pathfinder.Result.Found>(Pathfinder(calm).to(5, 10, Node(0, 10), surf.copy(weights = weights)))
        assertEquals(5, calmWeighted.route.edges.size)
    }

    @Test
    fun aTrainersSightIsWalkedAroundWhenTheDetourIsCheaperThanABattle() {
        // A trainer at (2,0) looks south over the corridor's tile (2,2); the way around is 8 tiles longer.
        val map = area(
            ".....",
            "##.##",
            ".....",
            ".###.",
            ".###.",
            ".....",
        )
        val trainer = Overlay(listOf(LiveObject(2, 0, Direction.SOUTH, sightRange = 2)))
        val walkThrough = assertIs<Pathfinder.Result.Found>(Pathfinder(map, trainer).to(4, 2, Node(0, 2), RouteOptions()))
        assertTrue(walkThrough.route.warnings.contains(RouteWarning.PassesTrainerSight))
        val weighted = assertIs<Pathfinder.Result.Found>(Pathfinder(map, trainer).to(4, 2, Node(0, 2), RouteOptions(weights = StepWeights(trainerSight = 500))))
        assertTrue(RouteWarning.PassesTrainerSight !in weighted.route.warnings, weighted.route.toString())
        // A beaten trainer (no sight range) costs nothing: the corridor again.
        val beaten = Overlay(listOf(LiveObject(2, 0, Direction.SOUTH)))
        assertEquals(4, assertIs<Pathfinder.Result.Found>(Pathfinder(map, beaten).to(4, 2, Node(0, 2), RouteOptions(weights = StepWeights(trainerSight = 500)))).route.edges.size)
    }

    @Test
    fun theWorldRouterCountsTheSameWeights() {
        val map = area(
            ".....",
            "\"\"\"\".",
            "#####",
        )
        val world = object : WorldSource {
            override fun areaOf(zoneId: Int): Area? = if (zoneId == zone) map else null
        }
        val options = RouteOptions(weights = StepWeights(landEncounter = mapOf(zone to 10)))
        val route = WorldRouter(world).route(zone, Node(0, 1), options) { it.node.x == 4 && it.node.y == 1 }
        val tiles = route!!.places.map { it.node }
        assertTrue(tiles.none { map.tile(it.x, it.y)?.kind == TileKind.TallGrass }, tiles.toString())
    }

    // region A Repel wearing off on the way

    /**
     * A world whose zone [zone] rolls [land] on the land walking ([landRunning] running), none at all under a Repel (a
     * lead stronger than every wild Pokémon there).
     */
    private fun repelWorld(land: Double, landRunning: Double = land) = object : WorldSource {
        override fun areaOf(zoneId: Int): Area? = null
        override val zoneCount = 10
        override val encounterTables = EncounterTables.DECODED
        override fun encounterChance(zoneId: Int, water: Boolean, conditions: EncounterConditions) = when {
            zoneId != zone || water || conditions.repelLevel != null -> 0.0
            conditions.landMovement == MovementMode.WALK -> land
            else -> landRunning
        }
    }

    /** Running everywhere, a Repel of [steps] steps left keeping every wild Pokémon of the zone away; 10 steps a grass tile without it. */
    private fun repelled(steps: Int) = StepWeights.of(repelWorld(land = 0.08), EncounterConditions(MovementMode.RUN, repelLevel = 50, repelSteps = steps))

    @Test
    fun aRepelCoversTheStepsItHasLeftThenTheWeightsWithoutItApply() {
        val weights = repelled(steps = 3)
        val grass = TileInfo(false, TileKind.TallGrass)
        assertEquals(RepelCover(3, StepWeights(landEncounter = mapOf(zone to 10), trainerSight = 500)), weights.repel)
        // The moves after 0, 1 and 2 steps are covered (the 3rd step brings the counter to 0: the game says it wore off
        // instead of checking), the ones after 3 steps aren't.
        assertEquals(listOf(0, 0, 0, 10, 10), (0..4).map { weights.after(it).encounter(grass, zone) })
        // Without a known count of steps left, the Repel lasts the whole trip (no cover to count).
        assertEquals(null, StepWeights.of(repelWorld(land = 0.08), EncounterConditions(MovementMode.RUN, repelLevel = 50)).repel)
        // A Repel that changes nothing (no wild Pokémon anywhere): nothing to count either.
        assertEquals(null, StepWeights.of(repelWorld(land = 0.0), EncounterConditions(MovementMode.RUN, repelLevel = 50, repelSteps = 3)).repel)
        // Walking onto the grass where running doubles the roll: run through while the Repel keeps everything away,
        // walked once it's gone (the walker's B: MovePlans.runOnto).
        val walking = StepWeights.of(repelWorld(land = 0.04, landRunning = 0.08), EncounterConditions(MovementMode.WALK, repelLevel = 50, travelMovement = MovementMode.RUN, repelSteps = 2))
        assertEquals(listOf(false, false, true), (0..2).map { walking.after(it).walksOnto(grass, zone) })
        assertEquals(listOf(0, 0, 6), (0..2).map { walking.after(it).moveOnto(grass, zone) })
    }

    @Test
    fun theStepsTheGameCountsAreTheMovesThePlayerMakes() {
        // A step, a ledge jump and the step starting a slide count one each; a ride, Surf's hop, a Strength push or a
        // lever none (the game counts the player's own moves only).
        val node = Node(0, 0)
        val edges = listOf(
            Edge.Step(node, Direction.EAST, 1),
            Edge.Jump(node, Direction.SOUTH),
            Edge.Teleport(node, Direction.EAST, node, 8),
            FieldMoveEdge(node, Direction.EAST, FieldMoveKind.SURF, listOf(node), 14),
            Edge.Slide(node, Direction.EAST, listOf(node, node)),
            Edge.Slide(node, Direction.SOUTH, listOf(node, node), scripted = true),
            FieldMoveEdge(node, Direction.EAST, FieldMoveKind.CUT, listOf(node), 9),
            PushEdge(node, Direction.EAST, 1 to 0, 2 to 0, needsStrength = true),
            SwitchEdge(node, Direction.NORTH, "switch:1"),
            Edge.Step(node, Direction.EAST, 1),
        )
        assertEquals(listOf(1, 1, 0, 0, 1, 0, 1, 0, 0, 1), edges.map { it.gameSteps })
        assertEquals(listOf(0, 1, 2, 2, 2, 3, 3, 4, 4, 4), Route(edges, emptyList()).stepsBefore)
    }

    /** The cost of [route] the way the game plays it: each move and turn weighed by the steps counted before it. */
    private fun replayCost(pathfinder: Pathfinder, start: Node, route: Route, options: RouteOptions): Int {
        var at = start
        var direction: Direction? = null
        var cost = 0
        route.edges.zip(route.stepsBefore).forEach { (edge, taken) ->
            val now = options.copy(weights = options.weights.after(taken))
            val move = pathfinder.neighbours(at, now).single { it.to == edge.to && it.direction == edge.direction }
            cost += move.cost + if (direction != null && move.direction != direction) pathfinder.turnCostAt(at, now) else 0
            at = move.to
            direction = move.endDirection
        }
        return cost
    }

    @Test
    fun aRepelExactlyLongEnoughCrossesTheGrassOneStepShortGoesAround() {
        // Straight east through 5 grass tiles (6 moves), or around them by the top row (8 moves and 2 turns).
        val map = area(
            ".......",
            ".\"\"\"\"\".",
            "#######",
        )
        val pathfinder = Pathfinder(map)
        fun route(steps: Int) = assertIs<Pathfinder.Result.Found>(pathfinder.to(6, 1, Node(0, 1), RouteOptions(weights = repelled(steps)))).route
        // The 5th step is the last grass tile: 5 steps left cover it.
        val enough = route(5)
        assertEquals(6, enough.edges.size, enough.toString())
        assertEquals(6, replayCost(pathfinder, Node(0, 1), enough, RouteOptions(weights = repelled(5))))
        // 4 steps left: the 5th grass tile would cost a whole encounter check (10): around is cheaper (8 + 2 × 2).
        val short = route(4)
        assertTrue(short.tiles().none { map.tile(it.x, it.y)?.kind == TileKind.TallGrass }, short.toString())
        assertEquals(12, replayCost(pathfinder, Node(0, 1), short, RouteOptions(weights = repelled(4))))
        // Taken as lasting the whole way (the previous rule), it went through the grass: 6 + 10 the way the game plays.
        assertEquals(16, replayCost(pathfinder, Node(0, 1), enough, RouteOptions(weights = repelled(4))))
    }

    @Test
    fun aShortRepelTakesTheGrassItCoversNowRatherThanTheGrassLater() {
        // Two ways of the same length around a wall: grass right away (top), or grass at the end (bottom). Counted as
        // lasting, both are free and equal; with 4 steps left only the top one is covered.
        val map = area(
            ".\"\"\".....",
            ".#######.",
            ".....\"\"\".",
        )
        val options = RouteOptions(weights = repelled(4))
        val pathfinder = Pathfinder(map)
        val route = assertIs<Pathfinder.Result.Found>(pathfinder.to(8, 1, Node(0, 1), options)).route
        assertEquals(Node(0, 0), route.edges.first().to, route.toString())
        // Every grass tile entered within the Repel's steps: 10 moves and 2 turns, nothing else.
        assertEquals(10 + 2 * RouteOptions.TURN_COST, replayCost(pathfinder, Node(0, 1), route, options))
        // The world router counts the same.
        val world = object : WorldSource {
            override fun areaOf(zoneId: Int): Area? = if (zoneId == zone) map else null
        }
        val routed = WorldRouter(world).route(zone, Node(0, 1), options) { it.node.x == 8 && it.node.y == 1 }!!
        assertEquals(Node(0, 0), routed.places.first().node)
        assertEquals(10 + 2 * RouteOptions.TURN_COST, routed.cost)
    }

    @Test
    fun withAnyRepelLeftTheRouteCostsWhatAnExhaustiveSearchFinds() {
        // Random maps with grass, cave floors, water and ledges, a Repel of 0 to 12 steps left: the two-pass search
        // (lower bound, then the Pareto labels of cost and steps left) costs exactly what a search over every
        // (node, direction, steps counted) state finds, and its cost is the one the route really has.
        val random = kotlin.random.Random(11)
        val kinds = "......#\"\"\"c~v>"
        var compared = 0
        repeat(300) {
            val rows = Array(7) { (0 until 9).map { kinds[random.nextInt(kinds.length)] }.joinToString("") }
            rows[0] = "." + rows[0].drop(1)
            val map = area(*rows)
            val covered = StepWeights(landEncounter = mapOf(zone to random.nextInt(3)), surfEncounter = mapOf(zone to random.nextInt(3)))
            val after = StepWeights(landEncounter = mapOf(zone to 3 + random.nextInt(12)), surfEncounter = mapOf(zone to 2 + random.nextInt(8)))
            val weights = covered.copy(repel = RepelCover(random.nextInt(13), after))
            val options = RouteOptions(canSurf = random.nextBoolean(), acceptOneWay = true, turnCost = random.nextInt(4), weights = weights)
            val goal = Node(random.nextInt(9), random.nextInt(7))
            val pathfinder = Pathfinder(map)
            val expected = exhaustive(pathfinder, Node(0, 0), goal, options)
            if (expected == null || goal == Node(0, 0)) return@repeat
            val route = assertIs<Pathfinder.Result.Found>(pathfinder.route(Node(0, 0), options) { it.x == goal.x && it.y == goal.y }, rows.joinToString("\n")).route
            assertEquals(expected, replayCost(pathfinder, Node(0, 0), route, options), rows.joinToString("\n") + " → $goal $options")
            compared++
        }
        assertTrue(compared > 100, "$compared maps compared")
    }

    /** The cheapest cost from [start] to [goal] over every (node, direction, steps counted) state, without any cut. */
    private fun exhaustive(pathfinder: Pathfinder, start: Node, goal: Node, options: RouteOptions): Int? {
        val cap = options.weights.repel?.steps ?: 0
        data class S(val node: Node, val direction: Direction?, val taken: Int)
        val dist = HashMap<S, Int>()
        val queue = ArrayList<Pair<S, Int>>()
        dist[S(start, null, 0)] = 0
        queue += S(start, null, 0) to 0
        while (queue.isNotEmpty()) {
            val next = queue.minBy { it.second }
            queue.remove(next)
            val (state, d) = next
            if (d > dist.getValue(state)) continue
            if (state.node.x == goal.x && state.node.y == goal.y && state.node != start) return d
            val now = options.copy(weights = options.weights.after(state.taken))
            for (edge in pathfinder.neighbours(state.node, now)) {
                val turn = if (state.direction != null && edge.direction != state.direction) pathfinder.turnCostAt(state.node, now) else 0
                val cost = d + edge.cost + turn
                val to = S(edge.to, edge.endDirection, minOf(cap, state.taken + edge.gameSteps))
                if (cost < (dist[to] ?: Int.MAX_VALUE)) {
                    dist[to] = cost
                    queue += to to cost
                }
            }
        }
        return null
    }

    // endregion
}
