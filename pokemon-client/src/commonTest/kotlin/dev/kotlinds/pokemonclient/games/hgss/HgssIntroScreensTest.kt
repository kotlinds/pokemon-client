package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IntroInputs
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.StateView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screens before the game on real HeartGold (USA) snapshots captured on the bench from power-on: the intro movie
 * before and after it can be skipped, the title screen while it ignores input (loading, its 30 first iterations) and
 * once it takes A / START / a touch, the main menu reached by a touch of the title, and the communication error
 * melonDS shows there.
 */
class HgssIntroScreensTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)

    private fun state(name: String): GameState = game.state(HgssFixtures.load(name))

    private val aStartOrTouch = IntroInputs(setOf(Button.A, Button.START), touchAnywhere = true)

    @Test
    fun theIntroMovieIgnoresInputUntilItsFirstSceneAllowsSkipping() {
        assertEquals(Screen.Intro(IntroStage.INTRO_MOVIE, Awaiting.ANIMATION), state("hg_intro_movie_unskippable").screen)
        assertEquals(Screen.Intro(IntroStage.INTRO_MOVIE, Awaiting.INPUT, aStartOrTouch), state("hg_intro_movie_input").screen)
    }

    @Test
    fun theTitleScreenIgnoresInputWhileItLoads() {
        assertEquals(Screen.Intro(IntroStage.TITLE_SCREEN, Awaiting.ANIMATION), state("hg_title_not_ready").screen)
    }

    @Test
    fun theTitleScreenTakesAStartOrATouchOnceReady() {
        assertEquals(Screen.Intro(IntroStage.TITLE_SCREEN, Awaiting.INPUT, aStartOrTouch), state("hg_title_ready").screen)
    }

    @Test
    fun theAgentIsToldWhatPassesTheTitleScreenAndTheActionThatContinues() {
        val view = StateView.screen(state("hg_title_ready").screen)
        assertEquals("intro:title_screen", view["kind"]!!.jsonPrimitive.content)
        assertEquals("input", view["awaiting"]!!.jsonPrimitive.content)
        assertEquals("title_screen", view["detail"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("press a", "press start", "touch anywhere on the bottom screen"),
            (view["goes_on_with"] as JsonArray).map { it.jsonPrimitive.content },
        )
        assertTrue("continue_game" in view["hint"]!!.jsonPrimitive.content)

        val loading = StateView.screen(state("hg_title_not_ready").screen)
        assertNull(loading["goes_on_with"])
        assertTrue(loading["hint"]!!.jsonPrimitive.content.startsWith("it ignores input for a moment"))
    }

    @Test
    fun theMainMenuReachedByATouchOfTheTitleListsContinueByItsId() {
        val menu = assertIs<Screen.ListMenu>(state("hg_main_menu_touch").screen)
        assertEquals(MenuKind.MAIN_MENU, menu.kind)
        assertEquals(Cursor.At(0), menu.cursor) // the title's touch doesn't put the menu in touch mode (highlight drawn)
        assertEquals("option:continue", menu.entries.first().id)
    }

    @Test
    fun theCommunicationErrorOfTheMainMenuWaitsForA() {
        assertEquals(Screen.PressToContinue(ContinueReason.COMMUNICATION_ERROR), state("hg_comm_error").screen)
    }

    @Test
    fun continueGameIsOfferedOnEveryScreenBeforeTheGame() {
        val registry = ActionRegistry.of()
        listOf("hg_intro_movie_unskippable", "hg_intro_movie_input", "hg_title_not_ready", "hg_title_ready", "hg_main_menu_touch", "msg_main_menu").forEach { name ->
            assertTrue(registry.available(state(name), ActionMode.ASSISTED, game).any { it.name == "continue_game" }, name)
        }
    }
}
