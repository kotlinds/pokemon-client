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
 * '#' wall, '"' tall grass, '~' surfable water with wild Pokémon, '=' calm surfable water (none).
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
                else -> null
            }
        }
        return Area(0, "test", 0, 0, width, rows.size, tiles, zones = IntArray(width * rows.size) { zone })
    }

    /** A world whose zone [zone] rolls [land] on land and [surfing] on the water, whatever the conditions. */
    private fun world(land: Double, surfing: Double) = object : WorldSource {
        override fun areaOf(zoneId: Int): Area? = null
        override val zoneCount = 10
        override fun encounterChance(zoneId: Int, water: Boolean, conditions: EncounterConditions) = when {
            zoneId != zone -> 0.0
            water -> surfing
            else -> land
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
}
