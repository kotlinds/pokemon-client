package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.OVERWORLD
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.mon
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.move
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.FlyDestination
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.Region
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.kotlinds.pokemonclient.state.MapName

/**
 * Fly suggestions ([FlyAdvisor]) on an overworld strip of five maps, west to east: the player's town (map 1, x 0-19),
 * a long route (map 2, x 20-179), the place to reach (map 3, x 180-189), a town by it (map 4, x 190-209) and a far
 * town of another region (map 5, x 210-239). Walking from the player's town to map 3 is ~180 tiles; from map 4, one.
 */
class FlyAdvisorTest {

    private val zones = IntArray(WIDTH) { x ->
        when {
            x < 20 -> HOME
            x < 180 -> ROUTE
            x < 190 -> PLACE
            x < 210 -> NEAR_TOWN
            else -> FAR_TOWN
        }
    }
    private val area = Area(0, "strip", 0, 0, WIDTH, 1, Array(WIDTH) { TileInfo(false, TileKind.Floor) }, zones = zones)

    private fun world(regions: Map<Int, Int> = emptyMap()) = object : WorldSource {
        override fun areaOf(zoneId: Int) = area.takeIf { zoneId in 1..5 }
        override val zoneCount get() = 6
        override fun regionOf(zoneId: Int) = regions[zoneId]?.let { Region(it, "region $it") }
    }

    private val home = FlyDestination("fly:1", HOME, "Home Town")
    private val near = FlyDestination("fly:4", NEAR_TOWN, "Near Town")
    private val far = FlyDestination("fly:5", FAR_TOWN, "Far Town")

    /** HGSS's Fly rule (the move, the Storm Badge by id). */
    private val fly = hgssFieldMoves(FieldMoveKind.FLY) as dev.kotlinds.pokemonclient.world.FieldMoveRule

    private fun state(destinations: List<FlyDestination>, x: Int = 0, badges: Set<Int> = setOf(fly.badgeId!!), knowsFly: Boolean = true) = GameState(
        0, OVERWORLD,
        PlayerInfo("ACE", 0, emptyList(), 1, badgeIds = badges, flyDestinations = destinations),
        listOf(mon(1, moves = if (knowsFly) listOf(move(fly.move.value, "Fly")) else emptyList())),
        null, null,
        FieldState(zones[x], MapName(zones[x], map = "map ${zones[x]}"), x, 0, 0, Direction.EAST, MovementMode.WALK, moving = false),
    )

    private fun advisor(regions: Map<Int, Int> = emptyMap()) = FlyAdvisor(FakeGame(OVERWORLD).apply { world = world(regions); fieldMoveRules = hgssFieldMoves })

    @Test
    fun theDestinationLandingNextToAFarPlaceIsSuggested() {
        val suggestion = assertNotNull(advisor().suggest(state(listOf(home, near, far)), PLACE))
        assertEquals(near, suggestion.destination)
        assertTrue(suggestion.fromLanding <= 10, "lands by it (searched from the middle of the place): ${suggestion.fromLanding}")
        assertTrue(suggestion.onFoot!! >= 170, "the walk: ${suggestion.onFoot}")
        assertTrue(suggestion.text.startsWith("nearest fly: Near Town (fly:4) lands ~"), suggestion.text)
    }

    @Test
    fun aDestinationOnThePlaceItselfLandsOnIt() {
        val onIt = FlyDestination("fly:3", PLACE, "The Place")
        val suggestion = assertNotNull(advisor().suggest(state(listOf(near, onIt)), PLACE))
        assertEquals(onIt, suggestion.destination)
        assertTrue(suggestion.text.startsWith("nearest fly: The Place (fly:3) lands on it (~17"), suggestion.text)
    }

    @Test
    fun nothingWhenWalkingIsNotMuchLonger() {
        assertNull(advisor().suggest(state(listOf(home, near), x = 170), PLACE))
    }

    @Test
    fun nothingWhenThePartyCantFly() {
        assertNull(advisor().suggest(state(listOf(near), knowsFly = false), PLACE))
        assertNull(advisor().suggest(state(listOf(near), badges = emptySet()), PLACE))
    }

    @Test
    fun aDestinationOfAnotherRegionNeedsTheHub() {
        val regions = mapOf(HOME to 0, ROUTE to 0, PLACE to 0, NEAR_TOWN to 1, FAR_TOWN to 1)
        assertNull(advisor(regions).suggest(state(listOf(home, near)), PLACE))
        assertEquals(near, advisor(regions).suggest(state(listOf(home, near.copy(fromAnyRegion = true))), PLACE)?.destination?.copy(fromAnyRegion = false))
        val hub = FlyDestination("fly:9", FAR_TOWN, "Hub", regionHub = true)
        assertEquals(near, advisor(regions).suggest(state(listOf(home, near, hub)), PLACE)?.destination)
    }

    private companion object {
        const val WIDTH = 240
        const val HOME = 1
        const val ROUTE = 2
        const val PLACE = 3
        const val NEAR_TOWN = 4
        const val FAR_TOWN = 5
    }
}
