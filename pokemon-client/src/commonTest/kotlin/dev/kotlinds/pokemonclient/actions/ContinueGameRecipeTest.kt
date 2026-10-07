package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.OVERWORLD
import dev.kotlinds.pokemonclient.actions.ScriptedUi.Companion.field
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IntroInputs
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `continue_game`: from the intro screens to the saved game, each input checked. The scripted title screen ignores
 * input until it is ready, like HeartGold's (its first 30 iterations), and the main menu may be reached by touch
 * (no highlight: the first D-pad press only draws it).
 */
class ContinueGameRecipeTest {

    private val inputs = IntroInputs(setOf(Button.A, Button.START), touchAnywhere = true)
    private val titleReady = Screen.Intro(IntroStage.TITLE_SCREEN, Awaiting.INPUT, inputs)
    private val titleLoading = Screen.Intro(IntroStage.TITLE_SCREEN, Awaiting.ANIMATION)
    private val loading = Screen.Intro(IntroStage.LOADING, Awaiting.ANIMATION)

    private fun mainMenu(cursor: Cursor) = Screen.ListMenu(
        MenuKind.MAIN_MENU,
        listOf(Entry("option:continue", "CONTINUE"), Entry("option:new_game", "NEW GAME", dangerous = true)),
        cursor, Topology.vertical(2),
    )

    /** A game whose title screen leads to the main menu ([viaTouch]: no highlight) and CONTINUE to the field. */
    private fun game(start: Screen, readyAt: Long = 0, viaTouch: Boolean = false, title: (Button) -> Boolean = { true }): ScriptedUi {
        val ui = ScriptedUi(start)
        var arrival: Long? = null
        ui.game.onFrame = { frame, screen ->
            when {
                screen == titleLoading && frame >= readyAt -> titleReady
                screen == loading && arrival != null && frame >= arrival!! -> {
                    ui.field = field(8, 13, Direction.NORTH)
                    OVERWORLD
                }
                else -> screen
            }
        }
        ui.onA = { screen, highlighted ->
            when {
                screen == titleReady && title(Button.A) -> mainMenu(if (viaTouch) Cursor.Hidden else Cursor.At(0))
                screen is Screen.ListMenu && highlighted == "option:continue" -> loading.also { arrival = ui.frame + 20 }
                screen is Screen.ListMenu && highlighted == "option:new_game" -> error("NEW GAME must never be picked")
                else -> screen
            }
        }
        ui.onDpad = { button, screen ->
            when {
                button == Button.START && screen == titleReady && title(Button.START) -> mainMenu(Cursor.At(0))
                screen is Screen.ListMenu && screen.cursor == Cursor.Hidden -> screen.copy(cursor = Cursor.At(0))
                else -> null
            }
        }
        return ui
    }

    @Test
    fun waitsUntilTheTitleScreenTakesInputThenContinues() {
        val ui = game(titleLoading, readyAt = 40)
        val pressedAt = mutableListOf<Long>()
        val press = ui.game.onPress
        ui.game.onPress = { button, screen -> pressedAt += ui.frame; press(button, screen) }

        val outcome = Recipes.COMMON.continueGame(GameAction.ContinueGame, ui.context())

        val done = assertIs<ActionOutcome.Done>(outcome)
        assertEquals("continued the saved game, at map 1 8,13", done.detail)
        assertTrue(pressedAt.all { it >= 40 }, "pressed while the title screen ignored input: $pressedAt")
        assertEquals(listOf(Button.A, Button.A), ui.game.presses) // the title, then CONTINUE
    }

    @Test
    fun theMainMenuReachedByTouchIsHandledLikeTheOther() {
        val ui = game(titleReady, viaTouch = true)
        assertIs<ActionOutcome.Done>(Recipes.COMMON.continueGame(GameAction.ContinueGame, ui.context()))
        assertTrue(ui.field != null)
    }

    @Test
    fun anIgnoredInputIsFollowedByTheNextOneTheScreenTakes() {
        val ui = game(titleReady, title = { it == Button.START })
        assertIs<ActionOutcome.Done>(Recipes.COMMON.continueGame(GameAction.ContinueGame, ui.context()))
        assertEquals(listOf(Button.A, Button.START, Button.A), ui.game.presses)
    }

    @Test
    fun aTouchIsTriedWhenTheButtonsAreIgnored() {
        val ui = game(titleReady, title = { false })
        ui.game.onTouch = { _, screen -> if (screen == titleReady) mainMenu(Cursor.Hidden) else screen }
        assertIs<ActionOutcome.Done>(Recipes.COMMON.continueGame(GameAction.ContinueGame, ui.context()))
        assertEquals(listOf(TouchPoint(128, 96)), ui.game.touches)
    }

    @Test
    fun aTitleScreenThatIgnoresEveryInputEndsInAnExplicitError() {
        val ui = game(titleReady, title = { false })
        val outcome = assertIs<ActionOutcome.Failed>(Recipes.COMMON.continueGame(GameAction.ContinueGame, ui.context()))
        val error = assertIs<ActionError.InputIgnored>(outcome.error)
        assertEquals("intro:title_screen", error.screen)
        assertEquals(listOf("press a", "press start", "touch 128,96", "press a"), error.tried)
        assertEquals(4, error.attempts) // the first try, then 3 corrections
    }

    @Test
    fun aCommunicationErrorOnTheMainMenuIsReported() {
        val ui = ScriptedUi(Screen.Intro(IntroStage.MAIN_MENU, Awaiting.ANIMATION))
        ui.game.onFrame = { frame, screen -> if (frame >= 10) Screen.PressToContinue(ContinueReason.COMMUNICATION_ERROR) else screen }
        val outcome = assertIs<ActionOutcome.Failed>(Recipes.COMMON.continueGame(GameAction.ContinueGame, ui.context()))
        assertEquals(ActionError.CommunicationError, outcome.error)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun withoutASaveTheNewGameIntroIsReported() {
        val ui = ScriptedUi(loading)
        ui.game.onFrame = { frame, screen -> if (frame >= 10) Screen.Dialogue(TextSource.INTRO, null, "Hello there!", Awaiting.INPUT) else screen }
        val outcome = assertIs<ActionOutcome.Failed>(Recipes.COMMON.continueGame(GameAction.ContinueGame, ui.context()))
        assertEquals(ActionError.NoSavedGame, outcome.error)
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun offeredOnlyBeforeTheGame() {
        val registry = ActionRegistry.of()
        fun offered(screen: Screen, inField: Boolean = false): Boolean {
            val state = GameState(0, screen, null, emptyList(), null, null, if (inField) field(1, 1, Direction.NORTH) else null)
            return registry.available(state, ActionMode.ASSISTED).any { it.name == "continue_game" }
        }
        assertTrue(offered(titleReady))
        assertTrue(offered(titleLoading))
        assertTrue(offered(Screen.Intro(IntroStage.INTRO_MOVIE, Awaiting.ANIMATION)))
        assertTrue(offered(loading))
        assertTrue(offered(mainMenu(Cursor.Hidden)))
        assertFalse(offered(Screen.Intro(IntroStage.NEW_GAME_INTRO, Awaiting.INPUT)))
        assertFalse(offered(OVERWORLD, inField = true))
        assertFalse(offered(Screen.ListMenu(MenuKind.START_MENU, listOf(Entry("option:bag", "BAG")), Cursor.At(0), Topology.vertical(1)), inField = true))
    }

    @Test
    fun refusedOutsideTheIntro() {
        val ui = ScriptedUi(OVERWORLD).also { it.field = field(1, 1, Direction.NORTH) }
        val outcome = assertIs<ActionOutcome.Failed>(Recipes.COMMON.continueGame(GameAction.ContinueGame, ui.context()))
        assertIs<ActionError.UnexpectedScreen>(outcome.error)
    }
}
