package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.data.MachineCompatibility.Fit
import dev.kotlinds.pokemonclient.state.MoveId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one rule of the TM party screen ([MachineCompatibility.of]), shared by its decoder and the actions. */
class MachineCompatibilityTest {

    private val focusPunch = MoveId(264)

    @Test
    fun `a compatible species is able, an incompatible one unable`() {
        assertEquals(Fit.ABLE, MachineCompatibility.of(isEgg = false, knownMoves = listOf(MoveId(33)), move = focusPunch, compatible = true))
        assertEquals(Fit.UNABLE, MachineCompatibility.of(isEgg = false, knownMoves = emptyList(), move = focusPunch, compatible = false))
    }

    @Test
    fun `knowing the move already is learned, before the species decides`() {
        assertEquals(Fit.LEARNED, MachineCompatibility.of(isEgg = false, knownMoves = listOf(focusPunch), move = focusPunch, compatible = true))
        // A move learned otherwise (level up, tutor) by a species the machine doesn't fit: still LEARNED, as the game shows.
        assertEquals(Fit.LEARNED, MachineCompatibility.of(isEgg = false, knownMoves = listOf(focusPunch), move = focusPunch, compatible = false))
    }

    @Test
    fun `an egg never learns`() {
        assertEquals(Fit.UNABLE, MachineCompatibility.of(isEgg = true, knownMoves = listOf(focusPunch), move = focusPunch, compatible = true))
    }

    @Test
    fun `an unknown machine move only lets the species decide`() {
        assertEquals(Fit.ABLE, MachineCompatibility.of(isEgg = false, knownMoves = emptyList(), move = null, compatible = true))
    }
}
