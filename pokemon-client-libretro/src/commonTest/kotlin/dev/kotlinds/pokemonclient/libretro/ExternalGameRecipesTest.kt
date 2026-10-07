package dev.kotlinds.pokemonclient.libretro

import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.ActionConditions
import dev.kotlinds.pokemonclient.actions.ActionError
import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionOutcome
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.actions.Availability
import dev.kotlinds.pokemonclient.actions.FieldControl
import dev.kotlinds.pokemonclient.actions.GameAction
import dev.kotlinds.pokemonclient.actions.PlanContext
import dev.kotlinds.pokemonclient.actions.Recipes
import dev.kotlinds.pokemonclient.actions.Step
import dev.kotlinds.pokemonclient.actions.UnavailableReason
import dev.kotlinds.pokemonclient.actions.andThen
import dev.kotlinds.pokemonclient.actions.then
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.Topology
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A game written outside the `pokemon-client` module (this test source set is another module: it sees only the
 * library's public API, never its `internal` members), the way someone writes their own game in their own project
 * before contributing it: its own instance of the common [Recipes], overriding a shared step ([Recipes.openParty]), a
 * recipe (`open_menu`) and an availability method ([Recipes.saveGameAvailability]), all `protected open`, written with
 * the library's public helpers the common recipes use too: [andThen] and [then] to chain checked steps,
 * [FieldControl] (whether the player walks freely) and [ActionConditions] (whether the player is in the field). The
 * overrides are played by the library's own entry points ([ActionRegistry]: the listing and the execution), never
 * called by the test.
 */
class ExternalGameRecipesTest {

    /** A scripted game: always walking with two Pokémon; records the buttons pressed. */
    private class ExternalGame : PokemonGame {
        val presses = mutableListOf<Button>()

        /** Every party opened through this game's own step. */
        var partiesOpened = 0

        /** The screen the game shows (walking by default). */
        var screen: Screen = Screen.Overworld(awaiting = Awaiting.INPUT)

        /** Whether START opens the start menu from the field (false: the game ignores it). */
        var startOpensMenu = true

        override val name = "External"

        override fun state(memory: Memory) = GameState(
            0, screen, null, listOf(mon(1), mon(2)), null, null, null,
        )

        private var held: Set<Button> = emptySet()
        override val inputProbe = InputProbe { held }

        /**
         * The game's own recipes: the common ones where it plays the same, its own party step and save condition. No
         * global shared object: an instance of its own.
         */
        override val recipes: Recipes = object : Recipes() {
            /**
             * This game opens its party with SELECT from the field (a step every party recipe goes through): checked
             * first ([FieldControl.inControl]), then the press, then what the screen shows ([andThen]).
             */
            override fun openParty(context: PlanContext): Step<GameState> {
                partiesOpened++
                return walking(context).andThen { _ ->
                    context.scope.tap(Button.SELECT)
                    Step.Failed(ActionError.UnexpectedScreen("the external game's party", context.state().screen))
                }
            }

            /**
             * This game opens its menu with START from the field, whatever the entry asked: pressed, then the screen
             * read back (never done blindly), and its steps end in the outcome ([then]).
             */
            override fun openMenu(action: GameAction.OpenMenu, context: PlanContext): ActionOutcome =
                walking(context).andThen { state ->
                    context.navigator.press(Button.START, state.screen) // pressed, then the screen awaited to change
                    val menu = context.navigator.settle()
                    if ((menu.screen as? Screen.ListMenu)?.kind == MenuKind.START_MENU) Step.Done(menu)
                    else Step.Failed(ActionError.UnexpectedScreen("the start menu", menu.screen))
                }.then { ActionOutcome.Done("opened ${action.entry} with START") }

            /** This game saves only at its save points: never from the field ([ActionConditions.inField]), hidden elsewhere. */
            override fun saveGameAvailability(state: GameState): Availability =
                if (ActionConditions.inField(state)) Availability.Unavailable(UnavailableReason.CANNOT_USE_HERE, "this game saves at its save points only", "go to a save point")
                else Availability.Hidden

            /** The player walking freely, or the typed reason not to press anything. */
            private fun walking(context: PlanContext): Step<GameState> {
                val state = context.state()
                return if (FieldControl.inControl(state)) Step.Done(state) else Step.Failed(ActionError.UnexpectedScreen("the field", state.screen))
            }
        }

        val console = object : ConsolePort {
            override val platform = Platform.NINTENDO_DS
            override var frame = 0L
            override val revision get() = frame
            override fun step(frames: Int, input: InputFrame) = repeat(frames) {
                frame++
                val pressed = input.buttons - held
                presses += pressed
                held = input.buttons
                if (Button.START in pressed && startOpensMenu && screen is Screen.Overworld) screen = startMenu()
            }
            override fun memorySize(region: MemoryRegion) = 16
            override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
            override fun framebuffer(): Frame? = null
            override fun saveState() = ByteArray(0)
            override fun loadState(state: ByteArray) = true
        }

        fun scope() = ActionScope(console, inputProbe)

        private fun startMenu() = Screen.ListMenu(MenuKind.START_MENU, emptyList(), Cursor.At(0), Topology.vertical(0))

        private fun mon(n: Long) = PartyMon(
            MonId(n, 1), n.toInt() - 1, Named(SpeciesId(155), "CYNDAQUIL"), null, 5, 20, 20, null, listOf("Fire"), null, null, emptyList(), emptyMap(), 0, null, false,
        )
    }

    private val registry = ActionRegistry.of()

    /** The game's own party step is played by a common recipe (`reorder_party`), run through the registry. */
    @Test
    fun anExternalGamesOwnStepIsPlayedByTheCommonRecipes() {
        val game = ExternalGame()
        val outcome = registry.execute(GameAction.ReorderParty(MonId(2, 1), 1), game.scope(), game)
        val error = assertIs<ActionError.UnexpectedScreen>(assertIs<ActionOutcome.Failed>(outcome).error)
        assertEquals("the external game's party", error.expected)
        assertEquals(1, game.partiesOpened)
        assertEquals(listOf(Button.SELECT), game.presses, "its own way, never the common start menu (X)")
    }

    /** The game's own condition decides the listing and the execution alike; another game keeps the common rule. */
    @Test
    fun anExternalGamesOwnConditionIsAppliedByTheListingAndTheExecution() {
        val game = ExternalGame()
        val state = game.state(game.scope().memory())
        assertTrue(registry.available(state, ActionMode.ASSISTED, game).none { it.name == "save_game" })
        val listed = registry.unavailable(state, ActionMode.ASSISTED, game).single { it.name == "save_game" }
        assertEquals(UnavailableReason.CANNOT_USE_HERE, listed.reason)
        assertEquals("this game saves at its save points only", listed.detail)
        val refused = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(registry.execute(GameAction.SaveGame, game.scope(), game)).error)
        assertEquals(UnavailableReason.CANNOT_USE_HERE to "this game saves at its save points only", refused.reason to refused.detail)
        assertTrue(game.presses.isEmpty(), "refused before any press")

        // Out of the field (the title screen): the same override says hidden, refused as a wrong screen.
        val talking = ExternalGame().also { it.screen = Screen.Intro(dev.kotlinds.pokemonclient.state.IntroStage.TITLE_SCREEN, Awaiting.INPUT) }
        val talkingState = talking.state(talking.scope().memory())
        assertTrue(registry.unavailable(talkingState, ActionMode.ASSISTED, talking).none { it.name == "save_game" })
        val wrongScreen = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(registry.execute(GameAction.SaveGame, talking.scope(), talking)).error)
        assertEquals(UnavailableReason.WRONG_SCREEN, wrongScreen.reason)

        // The same game with the common recipes, instantiated as they are: save_game offered in the field.
        val common = object : PokemonGame by game {
            override val recipes = Recipes()
        }
        assertTrue(registry.available(state, ActionMode.ASSISTED, common).any { it.name == "save_game" })
    }

    /**
     * A recipe of the game's own, chained with the public [andThen] / [then] and checked with [FieldControl]: run by
     * the registry from the field (its own START), and stopping before any press off the field.
     */
    @Test
    fun anExternalGamesOwnRecipeChainsItsStepsWithThePublicHelpers() {
        val game = ExternalGame()
        val done = assertIs<ActionOutcome.Done>(registry.execute(GameAction.OpenMenu("option:pokemon"), game.scope(), game))
        assertEquals("opened option:pokemon with START", done.detail)
        assertEquals(listOf(Button.START), game.presses)
        assertEquals(MenuKind.START_MENU, assertIs<Screen.ListMenu>(game.screen).kind)

        // START ignored by the game (the field still on screen): read back, the typed error, never "done".
        val ignoring = ExternalGame().also { it.startOpensMenu = false }
        val notOpened = assertIs<ActionError.UnexpectedScreen>(assertIs<ActionOutcome.Failed>(registry.execute(GameAction.OpenMenu("option:pokemon"), ignoring.scope(), ignoring)).error)
        assertEquals("the start menu", notOpened.expected)
        assertEquals(listOf(Button.START), ignoring.presses, "pressed once, then read back")

        // Not walking freely (the start menu is open): the step fails, the chain stops, nothing is pressed.
        val inMenu = ExternalGame().also { it.screen = Screen.ListMenu(MenuKind.START_MENU, emptyList(), Cursor.At(0), Topology.vertical(0)) }
        val failed = assertIs<ActionError.UnexpectedScreen>(assertIs<ActionOutcome.Failed>(registry.execute(GameAction.OpenMenu("option:pokemon"), inMenu.scope(), inMenu)).error)
        assertEquals("the field", failed.expected)
        assertTrue(inMenu.presses.isEmpty(), "stopped before any press")
    }
}
