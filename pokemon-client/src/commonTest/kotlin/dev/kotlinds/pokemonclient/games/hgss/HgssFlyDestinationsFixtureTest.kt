package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.FlyDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The fly destinations visited, read from the save's flags anywhere (not only on the fly map), and the places of the
 * story steps the fly suggestions measure to. Fixture: home in New Bark Town just after the League win (bench).
 */
class HgssFlyDestinationsFixtureTest {

    private val state = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("fly_destinations_new_bark"))

    @Test
    fun theVisitedDestinationsAreReadFromTheFlags() {
        val destinations = assertNotNull(state.player).flyDestinations
        assertEquals(
            listOf("fly:58", "fly:60", "fly:67", "fly:73", "fly:74", "fly:75", "fly:76", "fly:77", "fly:78", "fly:87", "fly:89", "fly:88", "fly:30"),
            destinations.map { it.id },
        )
        // Victory Road's fly point lands on Route 26 and, like Indigo Plateau, can be chosen from Kanto too.
        assertEquals(FlyDestination("fly:30", 30, "Victory Road", fromAnyRegion = true), destinations.last())
        assertTrue(destinations.single { it.regionHub }.id == "fly:58")
    }

    @Test
    fun theGoalSaysWhereItHappens() {
        val goal = assertNotNull(state.story?.goal)
        assertEquals("kanto:ss_ticket", goal.id)
        assertEquals(60, goal.place) // New Bark Town
    }

    @Test
    fun everyPlaceBelongsToAStep() {
        assertTrue(HgssStoryTable.places.keys.all { HgssStoryTable.step(it) != null }, HgssStoryTable.places.keys.filter { HgssStoryTable.step(it) == null }.toString())
        assertEquals(90, HgssStoryTable.place("kanto:red")) // Mt. Silver
    }
}
