package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.StateView
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [HgssGame] gives on real snapshots of the early game (sparse fixtures, see [HgssFixtures]), taken live with
 * melonDS: the position, and the story's progress (open goals, steps completed) read from flags and vars.
 *  - d9: bedroom 2F, first controllable frame
 *  - mom1: 1F, Mom's first message fully printed (waiting for A)
 *  - nb1: outside the house in New Bark Town, after Mom's talk
 *  - lab1: Elm's lab after his speech, before choosing a starter
 *  - starter1: in the lab right after getting the starter
 *  - fol1: talking to the following Cyndaquil, message still printing
 *  - d1 / d5: intro screens
 */
class HgssGameTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)
    private fun state(name: String): GameState = game.state(HgssFixtures.load(name))
    private fun GameState.goals() = assertNotNull(story).openGoals.map { it.description }
    private fun GameState.completed() = assertNotNull(story).completed.map { it.id }

    @Test
    fun `bedroom position facing and first goal`() {
        val s = state("d9")
        assertEquals(Screen.Overworld(awaiting = Awaiting.INPUT), s.screen)
        val field = assertNotNull(s.field)
        assertEquals(64, field.mapId)
        assertEquals(6 to 6, field.x to field.y)
        assertEquals(Direction.SOUTH, field.facing)
        assertEquals("overworld · ${field.mapName} (6, 6) facing south", StateView.summary(s))
        assertEquals(listOf("Go downstairs (stairs in the top-left corner) and talk to Mom"), s.goals())
        assertEquals(emptyList(), s.completed())
    }

    @Test
    fun `mom's message waits for A`() {
        val s = state("mom1")
        assertEquals(Awaiting.INPUT, assertIs<Screen.Dialogue>(s.screen).awaiting)
        assertEquals(listOf("Talk to Mom on the first floor of your house"), s.goals())
    }

    @Test
    fun `follower message still printing is not awaiting input`() {
        assertNotEquals(Awaiting.INPUT, assertIs<Screen.Dialogue>(state("fol1").screen).awaiting)
    }

    @Test
    fun `story steps completed along the early game`() {
        val outside = state("nb1")
        assertTrue("johto:talk_to_mom" in outside.completed())
        assertContains(outside.goals().single(), "Elm's lab")

        val lab = state("lab1")
        assertTrue(lab.completed().containsAll(outside.completed()))
        assertContains(lab.goals().single(), "Choose a starter")

        val starter = state("starter1")
        assertEquals(1, starter.party.size)
        assertTrue(starter.completed().size > lab.completed().size)
        assertEquals(listOf("Walk to the lab's exit (Elm's aide has something for you)"), starter.goals())
    }

    @Test
    fun `intro screens are outside the field`() {
        assertNull(state("d1").field)
        assertNull(state("d5").field)
    }
}
