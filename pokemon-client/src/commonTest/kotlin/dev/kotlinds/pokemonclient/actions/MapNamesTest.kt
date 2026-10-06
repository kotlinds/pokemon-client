package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.FieldState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.kotlinds.pokemonclient.state.MapName

/** Map names as agents write them, and same-map warps (NOTES, Kanto part). */
class MapNamesTest {

    @Test
    fun aMapIsNamedWithoutItsTownOrItsGroundFloor() {
        assertTrue(WorldTravel.looseMatch("New Bark Elms Lab 1F", "Elms Lab"))
        assertTrue(WorldTravel.looseMatch("New Bark Elms Lab 1F", "elms lab 1F"))
        assertTrue(WorldTravel.looseMatch("Vermilion Pokecenter 1F", "Vermilion Pokecenter"))
        assertFalse(WorldTravel.looseMatch("New Bark Elms Lab 2F", "Elms Lab"))
        // Whole words only, and not too short.
        assertFalse(WorldTravel.looseMatch("New Bark Elms Lab 1F", "ab"))
        assertFalse(WorldTravel.looseMatch("Goldenrod Dept Store 1F", "ore"))
        assertTrue(WorldTravel.looseMatch("Goldenrod Dept Store 1F", "Dept Store"))
    }

    /**
     * NOTES (Kanto): `go_to "Seafoam Gym"` was refused as an unknown map. Blaine's gym moved into the Seafoam Islands:
     * its map is "Seafoam Islands Cinnabar Gym", every other one "<Town> Gym".
     */
    @Test
    fun aMapIsNamedByItsWordsInOrderSomeLeftOut() {
        assertTrue(WorldTravel.wordsMatch("Seafoam Islands Cinnabar Gym", "Seafoam Gym"))
        assertTrue(WorldTravel.wordsMatch("Seafoam Islands Cinnabar Gym", "seafoam islands gym"))
        assertTrue(WorldTravel.wordsMatch("Viridian Gym", "Viridian City Gym"))
        // In order, whole words, and two of them at least (one word is the suffix rule).
        assertFalse(WorldTravel.wordsMatch("Seafoam Islands Cinnabar Gym", "Gym Seafoam"))
        assertFalse(WorldTravel.wordsMatch("Seafoam Islands Cinnabar Gym", "Sea Gym"))
        assertFalse(WorldTravel.wordsMatch("Seafoam Islands Cinnabar Gym", "Gym"))
        assertFalse(WorldTravel.wordsMatch("Cerulean Gym", "Seafoam Gym"))
    }

    @Test
    fun townsAreNamedWithOrWithoutTownOrCity() {
        assertTrue(MapName.sameMapName("Cerulean City", "Cerulean"))
        assertTrue(MapName.sameMapName("Cerulean", "cerulean city"))
        assertFalse(MapName.sameMapName("Cerulean Gym", "Cerulean"))
    }

    private fun field(x: Int, y: Int) = FieldState(mapId = 410, mapName = MapName(410, map = "Saffron Gym"), x = x, y = y, height = 0, facing = null, movement = dev.kotlinds.pokemonclient.state.MovementMode.WALK, moving = false)

    /**
     * A pad to the same map (the Saffron Gym's) is a warp: the player jumps farther than a stride on the same map,
     * from one frame to the next or with the game's transition between the readings. A step or a ledge jump (two tiles)
     * isn't, nor is the step into the next zone of the same area; another map without the maps known is. A long move
     * read every few frames without a transition (a Rock Climb, a waterfall, a cart read by a wait) isn't a warp.
     */
    @Test
    fun aPadToTheSameMapIsAWarpAStepIsNot() {
        assertTrue(FieldControl.isWarp(null, field(18, 22), field(28, 25), transitionSeen = false, frames = 1))
        assertTrue(FieldControl.isWarp(null, field(18, 22), field(28, 25), transitionSeen = true, frames = 10))
        assertFalse(FieldControl.isWarp(null, field(18, 22), field(18, 23), transitionSeen = true, frames = 1))
        assertFalse(FieldControl.isWarp(null, field(18, 22), field(18, 24), transitionSeen = false, frames = 1))
        assertTrue(FieldControl.isWarp(null, field(18, 22), field(18, 23).copy(mapId = 59), transitionSeen = false, frames = 10))
        assertFalse(FieldControl.isWarp(null, field(18, 22), field(18, 27), transitionSeen = false, frames = 10))
    }
}
