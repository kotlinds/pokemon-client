package dev.kotlinds.pokemonclient.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one name normalizer ([normalizeName]) and the one map name model ([MapName]) every game and action uses. */
class NamesTest {

    @Test
    fun namesAreComparedWithoutCasePunctuationOrAccents() {
        assertEquals("pokeball", normalizeName("Poké Ball"))
        assertEquals("pokeball", normalizeName("POKE-BALL"))
        assertEquals("celestia", normalizeName("Célestia"))
        // The letters the former copies missed (ë ä ö ü ñ), and the ligatures.
        assertEquals(normalizeName("Noel Aaou Pinon"), normalizeName("Noël Ääöü Piñón"))
        assertEquals("oeufaess", normalizeName("Œuf Æß"))
        assertEquals("route29", normalizeName("Route 29"))
    }

    @Test
    fun aMapIsShownAsItsPlaceThenItsOwnNameAndFallsBackToItsId() {
        assertEquals("New Bark Town (New Bark Player House 2F)", MapName(4, "New Bark Town", "New Bark Player House 2F").toString())
        // The map's own name saying the same as the place: shown once.
        assertEquals("New Bark Town", MapName(1, "New Bark Town", "New Bark").toString())
        assertEquals("Route 29", MapName(2, "Route 29", "Route 29").toString())
        assertEquals("Twinleaf Town", MapName(3, location = "Twinleaf Town").toString())
        assertEquals("Floor 2", MapName(5, map = "Floor 2").toString())
        // Nothing known: the id form, which go_to accepts too.
        assertEquals("map:7", MapName(7).toString())
        assertEquals(7, MapName.parseIdForm(MapName(7).toString()))
        assertEquals("Ecruteak City", MapName(9, "Ecruteak City", "Ecruteak Gym").place)
    }

    @Test
    fun aMapAnswersToItsOwnNameItsDisplayFormAndItsId() {
        val gym = MapName(80, "Ecruteak City", "Ecruteak Gym")
        assertTrue(gym.isNamed("Ecruteak Gym"))
        assertTrue(gym.isNamed("ecruteak city (ecruteak gym)"))
        assertTrue(gym.isNamed("map:80"))
        assertFalse(gym.isNamed("map:81"))
        // The place alone names every map of the town: told apart by placeIs.
        assertFalse(gym.isNamed("Ecruteak City"))
        assertTrue(gym.placeIs("Ecruteak"))
        // A French place, typed without its accents.
        assertTrue(MapName(1, "Bourg Geon", "New Bark").placeIs("bourg-geon"))
        assertTrue(MapName(2, "Célestia", "Celestic Town").placeIs("Celestia"))
    }

    /** Names an agent noted from earlier versions' display forms still name the same map. */
    @Test
    fun formerDisplayFormsStillNameTheMap() {
        assertTrue(MapName(2, "Route 29", "Route 29").isNamed("Route 29 (Route 29)"))
        assertTrue(MapName(1, "New Bark Town", "New Bark").isNamed("New Bark Town (New Bark)"))
        assertTrue(MapName(411, "Twinleaf Town", "Twinleaf Town Player House 2F").isNamed("Twinleaf Town #411"))
        assertFalse(MapName(412, "Twinleaf Town", "Twinleaf Town Player House 1F").isNamed("Twinleaf Town #411"))
        assertFalse(MapName(411, "Twinleaf Town", "Twinleaf Town Player House 2F").isNamed("Sandgem Town #411"))
        // The new forms, unchanged.
        assertTrue(MapName(2, "Route 29", "Route 29").isNamed("Route 29"))
        assertFalse(MapName(80, "Ecruteak City", "Ecruteak Gym").isNamed("Ecruteak City (Ecruteak City)"))
    }
}
