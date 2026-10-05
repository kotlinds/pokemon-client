package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.RouteWarning
import dev.kotlinds.pokemonclient.world.StepWeights
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The wild encounter roll of HeartGold / SoulSilver (src/field/encounter_check.c) and the weights routes give it.
 * Route 1's table (`gs_enc_data.json`, R01): land rate 20, levels 2-6, no water encounters.
 */
class HgssEncountersTest {

    private val route1 = HgssEncounterTable(
        landRate = 20,
        surfRate = 0,
        landLevels = listOf(2, 2, 2, 2, 3, 3, 3, 3, 6, 4, 6, 4),
        surfLevels = List(5) { 0..0 },
    )

    private fun assertClose(expected: Double, actual: Double) = assertTrue(abs(expected - actual) < 1e-9, "expected $expected, got $actual")

    @Test
    fun theTwoRollsDependOnTheMovement() {
        // 20 walking (40 - 20), 40 running or surfing, 70 cycling (40 + 30); then the zone's rate.
        assertClose(0.20 * 0.20, HgssEncounters.chance(route1, water = false, EncounterConditions(MovementMode.WALK)))
        assertClose(0.40 * 0.20, HgssEncounters.chance(route1, water = false, EncounterConditions(MovementMode.RUN)))
        assertClose(0.70 * 0.20, HgssEncounters.chance(route1, water = false, EncounterConditions(MovementMode.BIKE)))
        assertClose(0.0, HgssEncounters.chance(route1, water = true, EncounterConditions(MovementMode.RUN)))
        val sea = route1.copy(surfRate = 15)
        // Surfing rolls 40 whatever the movement on land.
        assertClose(0.40 * 0.15, HgssEncounters.chance(sea, water = true, EncounterConditions(MovementMode.WALK)))
    }

    @Test
    fun aCleanseTagOnTheLeadCutsTheRate() {
        // rate * 2 / 3 in integers: 20 → 13.
        assertClose(0.40 * 0.13, HgssEncounters.chance(route1, water = false, EncounterConditions(MovementMode.RUN, leadItem = 224)))
        assertClose(0.40 * 0.20, HgssEncounters.chance(route1, water = false, EncounterConditions(MovementMode.RUN, leadItem = 1)))
    }

    @Test
    fun aRepelKeepsAwayEveryWeakerWildPokemon() {
        val run = EncounterConditions(MovementMode.RUN)
        // A level 54 lead: nothing on Route 1 (levels 2-6) gets through.
        assertClose(0.0, HgssEncounters.chance(route1, water = false, run.copy(repelLevel = 54)))
        // A level 4 lead: the slots of level 4 and more (slots 8-11: 4 + 4 + 1 + 1 = 10%) still appear.
        assertClose(0.40 * 0.20 * 0.10, HgssEncounters.chance(route1, water = false, run.copy(repelLevel = 4)))
        // A level 2 lead: everything (the game repels only lower levels).
        assertClose(0.40 * 0.20, HgssEncounters.chance(route1, water = false, run.copy(repelLevel = 2)))
        // Surfing levels are rolled uniformly: slot 0 (60%) at 10-20 against a level 16 lead lets 5 levels of 11 through.
        val sea = route1.copy(surfRate = 10, surfLevels = listOf(10..20, 30..30, 30..30, 30..30, 30..30))
        assertClose(0.40 * 0.10 * (0.60 * 5 / 11 + 0.40), HgssEncounters.chance(sea, water = true, run.copy(repelLevel = 16)))
    }

    @Test
    fun theRomTablesAreTheDecompsOnes() {
        val world = HgssWorldRom.require()
        // Route 1 (zone 9): bank 111 of a/0/3/7.
        assertEquals(route1, assertNotNull(world.encounters(9)).copy(surfLevels = route1.surfLevels))
        // Viridian City (zone 50): surfing only, rate 15.
        val viridian = assertNotNull(world.encounters(50))
        assertEquals(0 to 15, viridian.landRate to viridian.surfRate)
        assertClose(0.40 * 0.20, world.encounterChance(9, water = false, EncounterConditions(MovementMode.RUN)))
        assertClose(0.40 * 0.15, world.encounterChance(50, water = true, EncounterConditions(MovementMode.RUN)))
        // Pallet Town has no table (ENCDATA_NA).
        assertClose(0.0, world.encounterChance(8, water = false, EncounterConditions(MovementMode.RUN)))
    }

    @Test
    fun route1IsWalkedOutsideTheGrassAndTheTrainersSight() {
        // Viridian → Pallet (the Route 1 gatehouse's south door to Pallet's edge), running: the old route crossed 46
        // grass tiles (49 checks with its turns there: about 3.9 battles expected); the weighted one goes round the grass where it can (the corridor
        // into Pallet can't be avoided) and stays out of the four School Kids' and Ace Trainers' sight.
        val world = HgssWorldRom.require()
        val area = assertNotNull(world.areaOf(9))
        val trainers = Overlay(area.people.filter { it.zone == 9 && it.sightRange > 0 }.map { LiveObject(it.x, it.y, it.facing, sightRange = it.sightRange) })
        val weights = StepWeights.of(world, EncounterConditions(MovementMode.RUN))
        assertEquals(10, weights.landEncounter[9])
        val options = RouteOptions(acceptOneWay = true, weights = weights)
        val found = assertIs<Pathfinder.Result.Found>(Pathfinder(area, trainers).route(Node(1036, 289), options) { it.x == 1041 && it.y == 352 })
        val grass = found.route.warnings.filterIsInstance<RouteWarning.CrossesTallGrass>().single().tiles
        assertTrue(grass <= 13, "crosses $grass grass tiles")
        assertTrue(RouteWarning.PassesTrainerSight !in found.route.warnings)
        val plain = assertIs<Pathfinder.Result.Found>(Pathfinder(area, trainers).route(Node(1036, 289), RouteOptions(acceptOneWay = true)) { it.x == 1041 && it.y == 352 })
        assertEquals(46, plain.route.warnings.filterIsInstance<RouteWarning.CrossesTallGrass>().single().tiles)
    }
}
